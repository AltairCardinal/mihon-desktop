package mihon.app.shizuku;

interface IShellInterface {
    int prepare(in AssetFileDescriptor apk, String transactionId) = 2;
    void commit(int sessionId, String transactionId) = 3;
    int sessionState(int sessionId, String transactionId) = 4;
    void abandon(int sessionId, String transactionId) = 5;

    void destroy() = 16777114;
}
