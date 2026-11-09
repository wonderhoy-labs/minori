package com.wonderhoylabs.minori.setup;

interface IShell {
    /** Runs a script as the shell user and returns what it printed, then its exit status on the last line. */
    String run(String script) = 1;

    /** Shizuku calls this transaction to end the service. */
    void destroy() = 16777114;
}
