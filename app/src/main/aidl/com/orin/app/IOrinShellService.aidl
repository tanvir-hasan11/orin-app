package com.orin.app;

interface IOrinShellService {
    void destroy() = 16777114;
    void exit() = 1;
    String exec(in String[] cmd, in String dir, int maxBytes, int timeoutMs) = 2;
    int getUid() = 3;
}
