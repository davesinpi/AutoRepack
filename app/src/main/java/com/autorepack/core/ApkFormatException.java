package com.autorepack.core;

/** Thrown when an APK, binary XML file or resource table can't be understood or patched. */
public class ApkFormatException extends RuntimeException {
    public ApkFormatException(String message) {
        super(message);
    }
}
