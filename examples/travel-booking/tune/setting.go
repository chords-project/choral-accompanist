package tune

import (
	"log/slog"
	"os"
	"runtime/debug"
	"strconv"
	"strings"
	"time"

	"github.com/bradfitz/gomemcache/memcache"
)

var (
	defaultGCPercent        int    = 100
	defaultMemCTimeout      int    = 2
	defaultMemCMaxIdleConns int    = 512
	defaultLogLevel         string = "info"
)

func setGCPercent() {
	ratio := defaultGCPercent
	if val, ok := os.LookupEnv("GC"); ok {
		ratio, _ = strconv.Atoi(val)
	}

	debug.SetGCPercent(ratio)
	slog.Info("Tune: setGCPercent to %d", ratio)
}

func setLogLevel() {
	slog.Warn("setLogLevel has not been ported to slog yet")

	// logLevel := defaultLogLevel
	// if val, ok := os.LookupEnv("LOG_LEVEL"); ok {
	// 	logLevel = val
	// }
	// switch logLevel {
	// case "", "ERROR", "error": // If env is unset, set level to ERROR.
	// 	zerolog.SetGlobalLevel(zerolog.ErrorLevel)
	// case "WARNING", "warning":
	// 	zerolog.SetGlobalLevel(zerolog.WarnLevel)
	// case "DEBUG", "debug":
	// 	zerolog.SetGlobalLevel(zerolog.DebugLevel)
	// case "INFO", "info":
	// 	zerolog.SetGlobalLevel(zerolog.InfoLevel)
	// case "TRACE", "trace":
	// 	zerolog.SetGlobalLevel(zerolog.TraceLevel)
	// default: // Set default log level to info
	// 	zerolog.SetGlobalLevel(zerolog.InfoLevel)
	// }

	// slog.Info("Set global log level", slog.String("log_level", logLevel))
}

func GetMemCTimeout() int {
	timeout := defaultMemCTimeout
	if val, ok := os.LookupEnv("MEMC_TIMEOUT"); ok {
		timeout, _ = strconv.Atoi(val)
	}
	slog.Info("Tune: GetMemCTimeout %d", timeout)
	return timeout
}

// Hack of memcache.New to avoid 'no server error' during running
func NewMemCClient(server ...string) *memcache.Client {
	ss := new(memcache.ServerList)
	err := ss.SetServers(server...)
	if err != nil {
		// Hack: panic early to avoid pod restart during running
		panic(err)
		//return nil, err
	} else {
		memc_client := memcache.NewFromSelector(ss)
		memc_client.Timeout = time.Second * time.Duration(GetMemCTimeout())
		memc_client.MaxIdleConns = defaultMemCMaxIdleConns
		return memc_client
	}
}

func NewMemCClient2(servers string) *memcache.Client {
	ss := new(memcache.ServerList)
	server_list := strings.Split(servers, ",")
	err := ss.SetServers(server_list...)
	if err != nil {
		// Hack: panic early to avoid pod restart during running
		panic(err)
		//return nil, err
	} else {
		memc_client := memcache.NewFromSelector(ss)
		memc_client.Timeout = time.Second * time.Duration(GetMemCTimeout())
		memc_client.MaxIdleConns = defaultMemCMaxIdleConns
		return memc_client
	}
}

func Init() {
	setLogLevel()
	setGCPercent()
}
