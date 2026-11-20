#ifndef ROYTOOLS_CORE_H
#define ROYTOOLS_CORE_H

#include <stdint.h>

int32_t RTCoreStart(const char *home, const uint8_t *config, int32_t configSize, int32_t mtu);
int32_t RTCoreReload(const uint8_t *config, int32_t configSize);
int32_t RTCoreStop(void);
int32_t RTCoreWritePacket(const uint8_t *packet, int32_t packetSize);
// A negative timeout waits until a packet is ready or the engine stops. The
// result is the packet size, 0 for no packet, -1 for stop, or -2 for a read error.
int32_t RTCoreReadPacket(uint8_t *buffer, int32_t capacity, int32_t timeoutMS);
int64_t RTCoreUploaded(void);
int64_t RTCoreDownloaded(void);
// 0 = unidentified, 1 = Wi-Fi, 2 = cellular.
void RTCoreSetTrafficNetwork(int32_t network);
void RTCoreProxyUsage(int64_t *wifi, int64_t *cellular);
int32_t RTCoreLastError(char *buffer, int32_t capacity);
int32_t RTCoreState(char *buffer, int32_t capacity);
// Fills one result per requested node in milliseconds (-1 if unavailable).
// A negative return value indicates an invalid request or a stopped core.
int32_t RTCoreTestProxyLatencies(const uint8_t *request, int32_t size, int32_t *output, int32_t count);

#endif
