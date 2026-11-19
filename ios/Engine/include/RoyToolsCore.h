#ifndef ROYTOOLS_CORE_H
#define ROYTOOLS_CORE_H

#include <stdint.h>

int32_t RTCoreStart(const char *home, const uint8_t *config, int32_t configSize, int32_t mtu);
int32_t RTCoreReload(const uint8_t *config, int32_t configSize);
int32_t RTCoreStop(void);
int32_t RTCoreWritePacket(const uint8_t *packet, int32_t packetSize);
int32_t RTCoreReadPacket(uint8_t *buffer, int32_t capacity, int32_t timeoutMS);
int64_t RTCoreUploaded(void);
int64_t RTCoreDownloaded(void);
int32_t RTCoreLastError(char *buffer, int32_t capacity);
int32_t RTCoreState(char *buffer, int32_t capacity);

#endif
