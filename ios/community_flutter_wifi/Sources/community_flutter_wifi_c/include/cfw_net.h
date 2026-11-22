#ifndef CFW_NET_H
#define CFW_NET_H

#include <stddef.h>

/// The resolver's DNS servers (libresolv `res_9_ninit` + `res_9_getservers`, public C API), written to [buf] as
/// numeric addresses separated by '\n'. Returns how many were written (0 when none or on failure).
int cfw_dns_servers(char *buf, size_t len);

/// The IPv4 default gateway of the interface [ifindex] (0 = any interface) from the kernel routing table
/// (`sysctl` CTL_NET / PF_ROUTE / NET_RT_FLAGS / RTF_GATEWAY, public BSD API). Writes the numeric address to
/// [buf] and returns 1; returns 0 when there is no such route.
int cfw_default_gateway(unsigned int ifindex, char *buf, size_t len);

#endif
