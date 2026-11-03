package tunnel

import (
	"github.com/metacubex/mihomo/tunnel/statistic"
)

func ResetStatistic() {
	statistic.DefaultManager.ResetStatistic()
}

func Now() (up int64, down int64) {
	return statistic.DefaultManager.Now()
}

func Total() (up int64, down int64) {
	return statistic.DefaultManager.Total()
}

func ProxyUsage() statistic.ProxyUsage {
	return statistic.DefaultManager.ProxyUsage()
}

func SetTrafficNetwork(network int32) {
	statistic.DefaultManager.SetTrafficNetwork(network)
}
