package org.corfudb.infrastructure;

import org.corfudb.common.config.ConfigParamsHelper;

public class CorfuServerCmdLine {

    public static final String METRICS_PORT_PARAM = "--metrics-port";
    public static final String HEALTH_PORT_PARAM = "--health-port";

    private CorfuServerCmdLine() {
        // prevent class initialization
    }

    /**
     * This string defines the command line arguments,
     * in the docopt DSL (see http://docopt.org) for the executable.
     * It also serves as the documentation for the executable.
     *
     * <p>Unfortunately, Java doesn't support multi-line string literals,
     * so you must concatenate strings and terminate with newlines.
     *
     * <p>Note that the java implementation of docopt has a strange requirement
     * that each option must be preceded with a space.
     */
    public static final String USAGE =
            "Corfu Server, the server for the Corfu Infrastructure.\n"
                    + "\n"
                    + "Usage:\n"
                    + "\tcorfu_server (-l <path>|-m) [-nsNA] [-a <address>|-q <interface-name>] "
                    + "[--network-interface-version=<interface-version>] "
                    + "[--max-replication-data-message-size=<msg-size>] "
                    + "[--max-replication-write-size=<max-replication-write-size>] "
                    + "[-c <ratio>] [-d <level>] [-p <seconds>] "
                    + "[--lrCacheSize=<cache-num-entries>]"
                    + "[--plugin=<plugin-config-file-path>]"
                    + "[--base-server-threads=<base_server_threads>] "
                    + "[--log-size-quota-percentage=<max_log_size_percentage>]"
                    + "[--reserved-space-bytes=<reserved_space_bytes>]"
                    + "[--logunit-threads=<logunit_threads>] [--management-server-threads=<management_server_threads>]"
                    + "[-e [-u <keystore> -f <keystore_password_file>] [-r <truststore> -w <truststore_password_file>] "
                    + "[-b] [-g -o <username_file> -j <password_file>] "
                    + "[-k <seqcache>] [-T <threads>] [-B <size>] [-i <channel-implementation>] "
                    + "[-H <seconds>] [-I <cluster-id>] [-x <ciphers>] [-z <tls-protocols>]] "
                    + "[--disable-cert-expiry-check-file=<file_path>]"
                    + "[--metrics]"
                    + "[" + METRICS_PORT_PARAM + "=<metrics_port>]"
                    + "[" + HEALTH_PORT_PARAM + "=<health_port>]"
                    + "[--snapshot-batch=<batch-size>] [--lock-lease=<lease-duration>]"
                    + "[--max-snapshot-entries-applied=<max-snapshot-entries-applied>]"
                    + "[--log-replication-fsm-threads=<log-replication-fsm-threads>]"
                    + "[--corfu-port-for-lr=<corfu-port-for-lr>]"
                    + "[-P <prefix>] [-R <retention>] <port>"
                    + "[--compaction-trigger-freq-ms=<compaction_trigger_freq_ms>]"
                    + "[--compactor-script=<compactor_script_path>]"
                    + "[--compactor-config=<compactor_config_path>]"
                    + "[--run-compactor-as-root]\n"
                    + "\n"
                    + "Options:\n"
                    + " -l <path>, --log-path=<path>                                             "
                    + "              Set the path to the storage file for the log unit.\n        "
                    + " -s, --single                                                             "
                    + "              Deploy a single-node configuration.\n"
                    + " -I <cluster-id>, --cluster-id=<cluster-id>"
                    + "              For a single node configuration the cluster id to use in UUID,"
                    + "              base64 format, or auto to randomly generate [default: auto].\n"
                    + " -T <threads>, --Threads=<threads>                                        "
                    + "              Number of corfu server worker threads, or 0 to use 2x the "
                    + "              number of available processors [default: 4].\n"
                    + " -P <prefix> --Prefix=<prefix>"
                    + "              The prefix to use for threads (useful for debugging multiple"
                    + "              servers) [default: ]."
                    + "                                                                          "
                    + "              The server will be bootstrapped with a simple one-unit layout."
                    + "\n -a <address>, --address=<address>                                      "
                    + "                IP address for the server router to bind to and to "
                    + "advertise to external clients.\n"
                    + " -q <interface-name>, --network-interface=<interface-name>                "
                    + "              The name of the network interface.\n"
                    + " --network-interface-version=<interface-version>                "
                    + "              The version of the network interface, IPv4 or IPv6(default).\n"
                    + " -i <channel-implementation>, --implementation <channel-implementation>   "
                    + "              The type of channel to use (auto, nio, epoll, kqueue)"
                    + "[default: nio].\n"
                    + " -m, --memory                                                             "
                    + "              Run the unit in-memory (non-persistent).\n"
                    + "              Data will be lost when the server exits!\n"
                    + " -c <ratio>, --cache-heap-ratio=<ratio>                                   "
                    + "              The ratio of jvm max heap size we will use for the the "
                    + "in-memory cache to serve requests from -\n"
                    + "                                                                          "
                    + "              (e.g. ratio = 0.5 means the cache size will be 0.5 * jvm max "
                    + "heap size\n"
                    + "                                                                          "
                    + "              If there is no log, then this will be the size of the log unit"
                    + "\n                                                                        "
                    + "                evicted entries will be auto-trimmed. [default: 0.5].\n"
                    + " -H <seconds>, --HandshakeTimeout=<seconds>                               "
                    + "              Handshake timeout in seconds [default: 10].\n               "
                    + "                                                                          "
                    + "              from the log. [default: -1].\n                              "
                    + "                                                                          "
                    + " -k <seqcache>, --sequencer-cache-size=<seqcache>                         "
                    + "               The size of the sequencer's cache. [default: 250000].\n    "
                    + " -B <size> --batch-size=<size>                                            "
                    + "              The read/write batch size used for data transfer operations [default: 100].\n"
                    + " -R <retention>, --metadata-retention=<retention>                         "
                    + "              Maximum number of system reconfigurations (i.e. layouts)    "
                    + "retained for debugging purposes [default: 1000].\n"
                    + " -p <seconds>, --compact=<seconds>                                        "
                    + "              The rate the log unit should compact entries (find the,\n"
                    + "                                                                          "
                    + "              contiguous tail) in seconds [default: 60].\n"
                    + " -d <level>, --log-level=<level>                                          "
                    + "              Set the logging level, valid levels are: \n"
                    + "                                                                          "
                    + "              ALL,ERROR,WARN,INFO,DEBUG,TRACE,OFF [default: INFO].\n"
                    + " -N, --no-sync                                                            "
                    + "              Disable syncing writes to secondary storage.\n"
                    + " -A, --no-auto-commit                                                     "
                    + "              Disable auto log commit.\n"
                    + " -e, --enable-tls                                                         "
                    + "              Enable TLS.\n"
                    + " -u <keystore>, --keystore=<keystore>                                     "
                    + "              Path to the key store.\n"
                    + " -f <keystore_password_file>, "
                    + "--keystore-password-file=<keystore_password_file>         Path to the file "
                    + "containing the key store password.\n"
                    + " -b, --enable-tls-mutual-auth                                             "
                    + "              Enable TLS mutual authentication.\n"
                    + " -r <truststore>, --truststore=<truststore>                               "
                    + "              Path to the trust store.\n"
                    + " -w <truststore_password_file>, "
                    + "--truststore-password-file=<truststore_password_file>   Path to the file "
                    + "containing the trust store password.\n"
                    + " -g, --enable-sasl-plain-text-auth                                        "
                    + "              Enable SASL Plain Text Authentication.\n"
                    + " -o <username_file>, --sasl-plain-text-username-file=<username_file>      "
                    + "              Path to the file containing the username for SASL Plain Text "
                    + "Authentication.\n"
                    + " -j <password_file>, --sasl-plain-text-password-file=<password_file>      "
                    + "              Path to the file containing the password for SASL Plain Text "
                    + "Authentication.\n"
                    + " -x <ciphers>, --tls-ciphers=<ciphers>                                    "
                    + "              Comma separated list of TLS ciphers to use.\n"
                    + "                                                                          "
                    + "              [default: " + ConfigParamsHelper.getTlsCiphersCSV() + "].\n"
                    + " -z <tls-protocols>, --tls-protocols=<tls-protocols>                      "
                    + "              Comma separated list of TLS protocols to use.\n"
                    + "                                                                          "
                    + "              [default: TLSv1.1,TLSv1.2].\n"
                    + " --disable-cert-expiry-check-file=<file_path>                             "
                    + "              Path to Disable Cert Expiry Check File. If this file is     "
                    + "              present, the certificate expiry checks are disabled.\n      "
                    + " --base-server-threads=<base_server_threads>                              "
                    + "              Number of threads dedicated for the base server [default: 1].\n"
                    + " --log-size-quota-percentage=<max_log_size_percentage>                    "
                    + "              The max size as percentage of underlying file-store size.\n "
                    + "              If this limit is exceeded "
                    + "              write requests will be rejected [default: 100.0].\n         "
                    + " --reserved-space-bytes=<reserved_space_bytes>                              "
                    + "              The reserved space that does not belong to                  "
                    + "              corfu's use [default: 0].\n                               "
                    + "                                                                          "
                    + " --compaction-trigger-freq-ms=<compaction_trigger_freq_ms>                "
                    + "               Frequency at which data will be trimmed & checkpointed\n   "
                    + " --compactor-script=<compactor_script_path>                               "
                    + "               Path to spawn the external corfu store compactor script\n  "
                    + "                                                                          "
                    + " --compactor-config=<compactor_config_path>                               "
                    + "               Path containing the external corfu store compactor config\n"
                    + "                                                                          "
                    + " --run-compactor-as-root                                               "
                    + "               To execute the compactor runner as a root user           \n"
                    + "                                                                          "
                    + " --management-server-threads=<management_server_threads>                  "
                    + "              Number of threads dedicated for the management server [default: 4].\n"
                    + "                                                                          "
                    + " --logunit-threads=<logunit_threads>                  "
                    + "              Number of threads dedicated for the logunit server [default: 4].\n"
                    + " --metrics                                                                "
                    + "              Enable metrics provider.\n                                  "
                    + " " + HEALTH_PORT_PARAM + "=<health_port>                                              "
                    + "              Enable health api and bind to this port.\n                  "
                    + " " + METRICS_PORT_PARAM + "=<metrics_port>                                              "
                    + "              Enable metrics api and bind to this port.\n                  "
                    + " --snapshot-batch=<batch-size>                                            "
                    + "              Snapshot (Full) Sync batch size (number of entries)\n       "
                    + " --log-replication-fsm-threads=<log-replication-fsm-threads>              "
                    +"                Number of LR FSM worker threads. \n                            "
                    + " --lrCacheSize=<cache-num-entries>"
                    + "              LR's cache max number of entries.\n                              "
                    + " --max-replication-data-message-size=<msg-size>                                       "
                    + "              The max size of replication data message in bytes.\n   "
                    + " --max-replication-write-size=<max-replication-write-size>"
                    + "              Max size of replicated data written by the SINK in a single corfu transaction. "
                    + "              Integer.MAX_VALUE by default\n "
                    + " --lock-lease=<lease-duration>                                            "
                    + "              Lock lease duration in seconds\n                            "
                    + " --max-snapshot-entries-applied=<max-snapshot-entries-applied>            "
                    + "              Max number of entries applied in a snapshot transaction.  50 by default."
                    + "              For special tables only\n.                                  "
                    + " --corfu-port-for-lr=<corfu-port-for-lr>                                  "
                    + "              Port on which Log Replicator can connect to Corfu [default: 9000]\n         "
                    + " -h, --help                                                               "
                    + "              Show this screen\n"
                    + " --version                                                                "
                    + "              Show version\n";
}
