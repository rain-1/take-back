// The picker window's only capabilities: read the list, report a choice.
"use strict";
const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("picker", {
  data: () => ipcRenderer.invoke("picker:data"),
  choose: (choice) => ipcRenderer.send("picker:choose", choice),
});
