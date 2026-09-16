// tb-app-audio — capture ONE application's audio on Windows.
//
//   tb-app-audio --list
//       JSON array of processes that currently have an audio session:
//       [{"pid":1234,"name":"Spotify.exe","active":true}, ...]
//
//   tb-app-audio --capture <pid>
//       Raw interleaved 32-bit float, 48 kHz stereo, on stdout, of everything
//       that process (and its child processes) plays. Runs until stdout closes
//       or the process exits.
//
//   tb-app-audio --resolve <hwnd>
//       Which application a window belongs to: {"pid":1234,"name":"Spotify.exe"}.
//
//   tb-app-audio --window <hwnd>
//       Like --capture, for the application that owns that window — so sharing
//       a window brings its sound without the user picking the app separately.
//
//   tb-app-audio --capture-except <pid>
//       Everything the computer plays EXCEPT that process and its children. Used
//       for whole-screen shares with take-back's own pid: all your sound, minus
//       the call, so nobody hears themselves echoed back.
//
//   tb-app-audio --tone <hz> [--seconds N] [--volume 0..1] [--muted]
//       Plays a sine through the default output device, so a test has an app
//       whose audio is known in advance to capture.
//
// Uses WASAPI "process loopback" (AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK),
// available from Windows 10 build 20348 / Windows 11 — the same API OBS's
// "Application Audio Capture" source is built on. A standalone process rather
// than a Node addon, so there's nothing to rebuild when Electron updates.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <audioclient.h>
#include <audiopolicy.h>
#include <mmdeviceapi.h>

// The Windows SDK ships these in audioclientactivationparams.h; MinGW-w64 (and
// so the Zig cross-compile used to build this from Linux) doesn't. They're
// small and documented, so declare them when the header is absent.
#if __has_include(<audioclientactivationparams.h>)
#include <audioclientactivationparams.h>
#else
typedef enum AUDIOCLIENT_ACTIVATION_TYPE {
  AUDIOCLIENT_ACTIVATION_TYPE_DEFAULT = 0,
  AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK = 1,
} AUDIOCLIENT_ACTIVATION_TYPE;
typedef enum PROCESS_LOOPBACK_MODE {
  PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE = 0,
  PROCESS_LOOPBACK_MODE_EXCLUDE_TARGET_PROCESS_TREE = 1,
} PROCESS_LOOPBACK_MODE;
typedef struct AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS {
  DWORD TargetProcessId;
  PROCESS_LOOPBACK_MODE ProcessLoopbackMode;
} AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS;
typedef struct AUDIOCLIENT_ACTIVATION_PARAMS {
  AUDIOCLIENT_ACTIVATION_TYPE ActivationType;
  union {
    AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS ProcessLoopbackParams;
  };
} AUDIOCLIENT_ACTIVATION_PARAMS;
#define VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK L"VAD\\Process_Loopback"
#endif
#include <fcntl.h>
#include <io.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>

static const REFERENCE_TIME HNS_PER_SEC = 10000000;

static std::string narrow(const wchar_t *w) {
  if (!w) return {};
  int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
  std::string s(n > 0 ? n - 1 : 0, '\0');
  if (n > 1) WideCharToMultiByte(CP_UTF8, 0, w, -1, &s[0], n, nullptr, nullptr);
  return s;
}

static std::string jsonEscape(const std::string &s) {
  std::string o;
  for (unsigned char c : s) {
    if (c == '"' || c == '\\') { o += '\\'; o += (char)c; }
    else if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
    else o += (char)c;
  }
  return o;
}

static std::string processName(DWORD pid) {
  std::string name;
  HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
  if (!h) return name;
  wchar_t path[MAX_PATH * 2];
  DWORD len = (DWORD)(sizeof path / sizeof path[0]);
  if (QueryFullProcessImageNameW(h, 0, path, &len)) {
    const wchar_t *base = wcsrchr(path, L'\\');
    name = narrow(base ? base + 1 : path);
  }
  CloseHandle(h);
  return name;
}

// The float32 / 48 kHz / stereo format everything downstream expects.
static WAVEFORMATEX floatFormat() {
  WAVEFORMATEX f = {};
  f.wFormatTag = WAVE_FORMAT_IEEE_FLOAT;
  f.nChannels = 2;
  f.nSamplesPerSec = 48000;
  f.wBitsPerSample = 32;
  f.nBlockAlign = (WORD)(f.nChannels * f.wBitsPerSample / 8);
  f.nAvgBytesPerSec = f.nSamplesPerSec * f.nBlockAlign;
  return f;
}

// ---- --list ----------------------------------------------------------------
// Walks the audio sessions on every active output device. A process can have
// sessions on several devices, so results are merged per pid.
static int listSessions() {
  std::map<DWORD, std::pair<std::string, bool>> found;
  IMMDeviceEnumerator *en = nullptr;
  HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                __uuidof(IMMDeviceEnumerator), (void **)&en);
  if (FAILED(hr)) { fprintf(stderr, "MMDeviceEnumerator: 0x%08lx\n", hr); return 1; }

  IMMDeviceCollection *devices = nullptr;
  if (SUCCEEDED(en->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &devices))) {
    UINT count = 0;
    devices->GetCount(&count);
    for (UINT d = 0; d < count; d++) {
      IMMDevice *dev = nullptr;
      if (FAILED(devices->Item(d, &dev))) continue;
      IAudioSessionManager2 *mgr = nullptr;
      if (SUCCEEDED(dev->Activate(__uuidof(IAudioSessionManager2), CLSCTX_ALL, nullptr, (void **)&mgr))) {
        IAudioSessionEnumerator *sessions = nullptr;
        if (SUCCEEDED(mgr->GetSessionEnumerator(&sessions))) {
          int n = 0;
          sessions->GetCount(&n);
          for (int i = 0; i < n; i++) {
            IAudioSessionControl *ctl = nullptr;
            if (FAILED(sessions->GetSession(i, &ctl))) continue;
            IAudioSessionControl2 *ctl2 = nullptr;
            if (SUCCEEDED(ctl->QueryInterface(__uuidof(IAudioSessionControl2), (void **)&ctl2))) {
              DWORD pid = 0;
              AudioSessionState state = AudioSessionStateInactive;
              ctl2->GetProcessId(&pid);
              ctl2->GetState(&state);
              // pid 0 is the "System Sounds" session, not an application.
              if (pid != 0 && ctl2->IsSystemSoundsSession() != S_OK) {
                auto &e = found[pid];
                if (e.first.empty()) e.first = processName(pid);
                e.second = e.second || state == AudioSessionStateActive;
              }
              ctl2->Release();
            }
            ctl->Release();
          }
          sessions->Release();
        }
        mgr->Release();
      }
      dev->Release();
    }
    devices->Release();
  }
  en->Release();

  printf("[");
  bool first = true;
  for (auto &kv : found) {
    printf("%s{\"pid\":%lu,\"name\":\"%s\",\"active\":%s}", first ? "" : ",", (unsigned long)kv.first,
           jsonEscape(kv.second.first.empty() ? "unknown" : kv.second.first).c_str(),
           kv.second.second ? "true" : "false");
    first = false;
  }
  printf("]\n");
  return 0;
}

// ---- --capture ---------------------------------------------------------------

// ActivateAudioInterfaceAsync reports back through this handler, on an MTA
// thread. It must be agile (IAgileObject) or activation fails with
// E_ILLEGAL_METHOD_CALL.
class ActivationHandler : public IActivateAudioInterfaceCompletionHandler, public IAgileObject {
 public:
  HANDLE done = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  HRESULT result = E_FAIL;
  IAudioClient *client = nullptr;

  STDMETHODIMP QueryInterface(REFIID riid, void **ppv) override {
    if (riid == IID_IUnknown || riid == __uuidof(IActivateAudioInterfaceCompletionHandler)) {
      *ppv = static_cast<IActivateAudioInterfaceCompletionHandler *>(this);
    } else if (riid == IID_IAgileObject) {
      *ppv = static_cast<IAgileObject *>(this);
    } else {
      *ppv = nullptr;
      return E_NOINTERFACE;
    }
    AddRef();
    return S_OK;
  }
  // Lives on main()'s stack for the whole capture; reference counting is moot.
  STDMETHODIMP_(ULONG) AddRef() override { return 2; }
  STDMETHODIMP_(ULONG) Release() override { return 1; }

  STDMETHODIMP ActivateCompleted(IActivateAudioInterfaceAsyncOperation *op) override {
    IUnknown *unk = nullptr;
    HRESULT activateHr = E_FAIL;
    result = op->GetActivateResult(&activateHr, &unk);
    if (SUCCEEDED(result)) result = activateHr;
    if (SUCCEEDED(result) && unk) {
      result = unk->QueryInterface(__uuidof(IAudioClient), (void **)&client);
    }
    if (unk) unk->Release();
    SetEvent(done);
    return S_OK;
  }
};

// ---- window -> owning application ----------------------------------------------

// Windows Store (UWP) apps are drawn inside a frame window owned by
// ApplicationFrameHost.exe; the app's real process owns a child CoreWindow.
// Capturing the frame host would capture nothing useful, so look past it.
struct ChildSearch { DWORD hostPid; DWORD found; };

static BOOL CALLBACK findRealChild(HWND child, LPARAM lp) {
  auto *s = (ChildSearch *)lp;
  DWORD pid = 0;
  GetWindowThreadProcessId(child, &pid);
  if (pid && pid != s->hostPid) { s->found = pid; return FALSE; }
  return TRUE;
}

static DWORD windowProcess(HWND hwnd) {
  DWORD pid = 0;
  if (!IsWindow(hwnd) || !GetWindowThreadProcessId(hwnd, &pid) || !pid) return 0;
  std::string name = processName(pid);
  if (_stricmp(name.c_str(), "ApplicationFrameHost.exe") == 0) {
    ChildSearch s = {pid, 0};
    EnumChildWindows(hwnd, findRealChild, (LPARAM)&s);
    // A minimized or suspended Store app detaches its CoreWindow from the
    // frame, leaving nothing to find. Say "no app" rather than return the frame
    // host: capturing that would silently share nothing.
    return s.found;
  }
  return pid;
}

static HWND parseHwnd(const wchar_t *text) {
  return (HWND)(ULONG_PTR)wcstoull(text, nullptr, 10);
}

static int resolveWindow(HWND hwnd) {
  DWORD pid = windowProcess(hwnd);
  if (!pid) { printf("null\n"); return 2; }
  printf("{\"pid\":%lu,\"name\":\"%s\"}\n", (unsigned long)pid, jsonEscape(processName(pid)).c_str());
  return 0;
}

static int capture(DWORD pid, PROCESS_LOOPBACK_MODE mode) {
  HANDLE target = OpenProcess(SYNCHRONIZE, FALSE, pid);
  if (!target) { fprintf(stderr, "no such process: %lu\n", (unsigned long)pid); return 2; }

  AUDIOCLIENT_ACTIVATION_PARAMS params = {};
  params.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
  params.ProcessLoopbackParams.TargetProcessId = pid;
  // INCLUDE: that process and its children (browsers and Electron apps play
  // audio from a helper process, not the one that owns the window).
  // EXCLUDE: everything else on the system.
  params.ProcessLoopbackParams.ProcessLoopbackMode = mode;

  PROPVARIANT pv;
  PropVariantInit(&pv);
  pv.vt = VT_BLOB;
  pv.blob.cbSize = sizeof params;
  pv.blob.pBlobData = (BYTE *)&params;

  ActivationHandler handler;
  IActivateAudioInterfaceAsyncOperation *op = nullptr;
  HRESULT hr = ActivateAudioInterfaceAsync(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK, __uuidof(IAudioClient),
                                           &pv, &handler, &op);
  if (FAILED(hr)) { fprintf(stderr, "ActivateAudioInterfaceAsync: 0x%08lx\n", hr); return 3; }
  WaitForSingleObject(handler.done, INFINITE);
  if (op) op->Release();
  if (FAILED(handler.result) || !handler.client) {
    fprintf(stderr, "process loopback activation failed: 0x%08lx (needs Windows 10 build 20348+)\n",
            handler.result);
    return 3;
  }
  IAudioClient *client = handler.client;

  // Process loopback has no mix format to query; the format is ours to pick,
  // and AUTOCONVERTPCM makes the engine convert the app's audio into it.
  WAVEFORMATEX fmt = floatFormat();
  hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED,
                          AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK |
                              AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
                          HNS_PER_SEC / 50 /* 20 ms */, 0, &fmt, nullptr);
  if (FAILED(hr)) { fprintf(stderr, "IAudioClient::Initialize: 0x%08lx\n", hr); return 4; }

  HANDLE ready = CreateEventW(nullptr, FALSE, FALSE, nullptr);
  client->SetEventHandle(ready);
  IAudioCaptureClient *cap = nullptr;
  hr = client->GetService(__uuidof(IAudioCaptureClient), (void **)&cap);
  if (FAILED(hr)) { fprintf(stderr, "GetService(IAudioCaptureClient): 0x%08lx\n", hr); return 4; }
  hr = client->Start();
  if (FAILED(hr)) { fprintf(stderr, "IAudioClient::Start: 0x%08lx\n", hr); return 4; }

  _setmode(_fileno(stdout), _O_BINARY);
  const HANDLE waits[2] = {ready, target};
  bool running = true;
  while (running) {
    DWORD w = WaitForMultipleObjects(2, waits, FALSE, 2000);
    if (w == WAIT_OBJECT_0 + 1) break;  // the app exited
    if (w == WAIT_TIMEOUT) continue;    // app silent: loopback delivers nothing
    UINT32 packet = 0;
    while (SUCCEEDED(cap->GetNextPacketSize(&packet)) && packet > 0) {
      BYTE *data = nullptr;
      UINT32 frames = 0;
      DWORD flags = 0;
      if (FAILED(cap->GetBuffer(&data, &frames, &flags, nullptr, nullptr))) { running = false; break; }
      size_t bytes = (size_t)frames * fmt.nBlockAlign;
      size_t wrote;
      if (flags & AUDCLNT_BUFFERFLAGS_SILENT) {
        static const float zeros[4096] = {0};
        wrote = 0;
        while (wrote < bytes) {
          size_t chunk = std::min(bytes - wrote, sizeof zeros);
          if (fwrite(zeros, 1, chunk, stdout) != chunk) break;
          wrote += chunk;
        }
      } else {
        wrote = fwrite(data, 1, bytes, stdout);
      }
      cap->ReleaseBuffer(frames);
      // The reader went away (Electron stopped sharing): we're done.
      if (wrote != bytes || fflush(stdout) != 0) { running = false; break; }
    }
  }

  client->Stop();
  cap->Release();
  client->Release();
  CloseHandle(ready);
  CloseHandle(target);
  return 0;
}

// ---- --tone ----------------------------------------------------------------------
// A known sound from a known process, for testing capture end to end.
static int tone(double hz, double seconds, float volume, bool muted) {
  IMMDeviceEnumerator *en = nullptr;
  if (FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                              __uuidof(IMMDeviceEnumerator), (void **)&en))) return 1;
  IMMDevice *dev = nullptr;
  if (FAILED(en->GetDefaultAudioEndpoint(eRender, eConsole, &dev))) { fprintf(stderr, "no output device\n"); return 1; }
  IAudioClient *client = nullptr;
  if (FAILED(dev->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void **)&client))) return 1;
  WAVEFORMATEX fmt = floatFormat();
  HRESULT hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED,
                                  AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
                                  HNS_PER_SEC / 10, 0, &fmt, nullptr);
  if (FAILED(hr)) { fprintf(stderr, "render Initialize: 0x%08lx\n", hr); return 1; }
  IAudioRenderClient *render = nullptr;
  client->GetService(__uuidof(IAudioRenderClient), (void **)&render);
  // --muted mutes this process's own audio session in the Windows mixer, so the
  // tone isn't heard on the speakers — or piped into a voice call by software
  // like VoiceMeeter — while a test captures it. Whether loopback still receives
  // a muted session's audio is exactly what such a test finds out.
  if (muted) {
    ISimpleAudioVolume *vol = nullptr;
    if (SUCCEEDED(client->GetService(__uuidof(ISimpleAudioVolume), (void **)&vol))) {
      vol->SetMute(TRUE, nullptr);
      vol->Release();
    }
  }
  UINT32 bufferFrames = 0;
  client->GetBufferSize(&bufferFrames);
  client->Start();

  const double step = 2.0 * 3.14159265358979 * hz / fmt.nSamplesPerSec;
  double phase = 0;
  DWORD end = GetTickCount() + (DWORD)(seconds * 1000);
  printf("%lu\n", (unsigned long)GetCurrentProcessId());  // tell the test who to capture
  fflush(stdout);
  while (GetTickCount() < end) {
    UINT32 padding = 0;
    client->GetCurrentPadding(&padding);
    UINT32 frames = bufferFrames - padding;
    BYTE *data = nullptr;
    if (frames && SUCCEEDED(render->GetBuffer(frames, &data))) {
      float *f = (float *)data;
      for (UINT32 i = 0; i < frames; i++) {
        float s = volume * (float)std::sin(phase);
        phase += step;
        f[i * 2] = s;
        f[i * 2 + 1] = s;
      }
      if (phase > 6.28318530717958) phase = std::fmod(phase, 6.28318530717958);
      render->ReleaseBuffer(frames, 0);
    }
    Sleep(10);
  }
  client->Stop();
  render->Release();
  client->Release();
  dev->Release();
  en->Release();
  return 0;
}

int wmain(int argc, wchar_t **argv) {
  if (FAILED(CoInitializeEx(nullptr, COINIT_MULTITHREADED))) return 1;
  int rc = 64;
  if (argc >= 2 && wcscmp(argv[1], L"--list") == 0) {
    rc = listSessions();
  } else if (argc >= 3 && wcscmp(argv[1], L"--capture") == 0) {
    rc = capture((DWORD)wcstoul(argv[2], nullptr, 10), PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE);
  } else if (argc >= 3 && wcscmp(argv[1], L"--capture-except") == 0) {
    rc = capture((DWORD)wcstoul(argv[2], nullptr, 10), PROCESS_LOOPBACK_MODE_EXCLUDE_TARGET_PROCESS_TREE);
  } else if (argc >= 3 && wcscmp(argv[1], L"--resolve") == 0) {
    rc = resolveWindow(parseHwnd(argv[2]));
  } else if (argc >= 3 && wcscmp(argv[1], L"--window") == 0) {
    DWORD pid = windowProcess(parseHwnd(argv[2]));
    if (!pid) { fprintf(stderr, "no such window\n"); rc = 2; }
    else rc = capture(pid, PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE);
  } else if (argc >= 3 && wcscmp(argv[1], L"--tone") == 0) {
    double seconds = 5;
    float volume = 0.05f;
    bool muted = false;
    for (int i = 3; i < argc; i++) {
      if (wcscmp(argv[i], L"--muted") == 0) { muted = true; continue; }
      if (i + 1 >= argc) break;
      if (wcscmp(argv[i], L"--seconds") == 0) seconds = wcstod(argv[++i], nullptr);
      else if (wcscmp(argv[i], L"--volume") == 0) volume = (float)wcstod(argv[++i], nullptr);
    }
    rc = tone(wcstod(argv[2], nullptr), seconds, volume, muted);
  } else {
    fwprintf(stderr, L"usage: tb-app-audio --list | --capture <pid> | --capture-except <pid> | --resolve <hwnd> | --window <hwnd>\n"
                    L"                    | --tone <hz> [--seconds N] [--volume 0..1] [--muted]\n");
  }
  CoUninitialize();
  return rc;
}
