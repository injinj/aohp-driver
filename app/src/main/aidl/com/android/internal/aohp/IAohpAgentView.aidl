/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * @hide
 */
package com.android.internal.aohp;

/** @hide */
interface IAohpAgentView {
    byte[] captureDisplay(int displayId, int quality);
    byte[] captureRegion(int displayId, int left, int top, int right, int bottom, int quality);
}
