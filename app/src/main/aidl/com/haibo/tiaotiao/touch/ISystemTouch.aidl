package com.haibo.tiaotiao.touch;

interface ISystemTouch {
    void destroy() = 16777114;
    // -1: guard refused; 0: failed/uncertain; 1: direct input completed; 2: compatible command completed.
    int tap(String expectedPackage, int x, int y, int width, int height, long deadlineUptimeMs) = 1;
}
