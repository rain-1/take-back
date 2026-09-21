package com.takeback.app

import android.content.Context
import android.widget.EditText

/**
 * A text field in a dialog, styled like the ones in the layouts: a rounded
 * panel rather than Material's bright accent underline, which was the loudest
 * thing on most of these screens.
 */
fun tbInput(ctx: Context): EditText = EditText(ctx, null, 0, R.style.TbInput)
