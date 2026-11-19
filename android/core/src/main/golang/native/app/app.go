package app

import (
	"strconv"
	"strings"
	"time"
	_ "time/tzdata" // Android may not expose IANA files in Go's default search paths.
)

var appVersionName string
var platformVersion int
var installedAppsUid = map[int]string{}

func ApplyVersionName(versionName string) {
	appVersionName = versionName
}

func ApplyPlatformVersion(version int) {
	platformVersion = version
}

func VersionName() string {
	return appVersionName
}

func PlatformVersion() int {
	return platformVersion
}

func NotifyInstallAppsChanged(uidList string) {
	uids := map[int]string{}

	for _, item := range strings.Split(uidList, ",") {
		kv := strings.Split(item, ":")
		if len(kv) == 2 {
			uid, err := strconv.Atoi(kv[0])
			if err != nil {
				continue
			}

			uids[uid] = kv[1]
		}
	}

	installedAppsUid = uids
}

func QueryAppByUid(uid int) string {
	return installedAppsUid[uid]
}

func NotifyTimeZoneChanged(name string, offset int) {
	location, err := time.LoadLocation(name)
	if err != nil {
		location = time.FixedZone(name, offset)
	}
	time.Local = location
}
