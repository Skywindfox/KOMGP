package komgp.util;

/** 极简日志封装：统一前缀，方便在终端里看运行状态。 */
public final class Log {
    private Log() {}

    private static volatile boolean verbose = System.getenv("RHY_VERBOSE") != null;

    public static void setVerbose(boolean v) { verbose = v; }

    public static void info(String msg) { emit("INFO", msg); }

    public static void warn(String msg) { emit("WARN", msg); }

    public static void error(String msg) { emit("ERROR", msg); }

    public static void error(String msg, Throwable t) {
        emit("ERROR", msg + " :: " + t);
        if (verbose) t.printStackTrace(System.err);
    }

    public static void debug(String msg) { if (verbose) emit("DEBUG", msg); }

    private static void emit(String level, String msg) {
        System.err.println("[" + level + "] " + msg);
    }
}
