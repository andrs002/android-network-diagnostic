package com.andrs002.networkdiagnostic;

// Runs only in Shizuku's explicitly authorized shell-user service.
interface IDataRecovery {
    void destroy() = 16777114;
    String cycleMobileData() = 1;
}
