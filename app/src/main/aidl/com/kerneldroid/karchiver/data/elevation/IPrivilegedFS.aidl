package com.kerneldroid.karchiver.data.elevation;

// listDir entry encoding per element: name + '\0' + isDir(0/1) + '\0' + size + '\0' + mtime + '\0' + mode, null on error; int results are 0 on success, nonzero on failure.
interface IPrivilegedFS {
    void destroy() = 16777114;
    List<String> listDir(String path) = 1;
    int deleteAll(in List<String> paths) = 2;
    int makeDirs(String path) = 3;
    int setMode(String path, int mode) = 4;
    ParcelFileDescriptor openFile(in String path, int mode) = 5;
}
