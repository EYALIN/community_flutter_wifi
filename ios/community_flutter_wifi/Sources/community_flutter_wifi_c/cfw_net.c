// Network facts iOS exposes only through public C APIs (no Swift module for <resolv.h>, no <net/route.h> in the
// iOS SDK): the resolver's DNS servers and the IPv4 default gateway. Nothing here needs a permission or a location.
#include "cfw_net.h"

#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <resolv.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/sysctl.h>
#include <sys/types.h>

int cfw_dns_servers(char *buf, size_t len) {
  if (buf == NULL || len == 0) return 0;
  buf[0] = '\0';
  struct __res_state state;
  memset(&state, 0, sizeof(state));
  if (res_ninit(&state) != 0) return 0;
  union res_sockaddr_union servers[MAXNS];
  memset(servers, 0, sizeof(servers));
  int n = res_getservers(&state, servers, MAXNS);
  int written = 0;
  size_t used = 0;
  for (int i = 0; i < n; i++) {
    const struct sockaddr *sa = (const struct sockaddr *)&servers[i];
    socklen_t salen;
    if (sa->sa_family == AF_INET) {
      salen = sizeof(struct sockaddr_in);
    } else if (sa->sa_family == AF_INET6) {
      salen = sizeof(struct sockaddr_in6);
    } else {
      continue;
    }
    char host[NI_MAXHOST];
    if (getnameinfo(sa, salen, host, sizeof(host), NULL, 0, NI_NUMERICHOST) != 0) continue;
    char *scope = strchr(host, '%');  // a link-local server's "%en0": the address alone is shown
    if (scope != NULL) *scope = '\0';
    size_t hl = strlen(host);
    if (used + hl + 2 > len) break;
    if (written > 0) buf[used++] = '\n';
    memcpy(buf + used, host, hl);
    used += hl;
    buf[used] = '\0';
    written++;
  }
  res_ndestroy(&state);
  return written;
}

// <net/route.h> is not in the iOS SDK. The routing-socket layout is XNU's (identical on macOS and iOS); the
// definitions below are copied from it with a prefix so they never clash with a header that does provide them.
struct cfw_rt_metrics {
  uint32_t rmx_locks;
  uint32_t rmx_mtu;
  uint32_t rmx_hopcount;
  int32_t rmx_expire;
  uint32_t rmx_recvpipe;
  uint32_t rmx_sendpipe;
  uint32_t rmx_ssthresh;
  uint32_t rmx_rtt;
  uint32_t rmx_rttvar;
  uint32_t rmx_pksent;
  uint32_t rmx_state;
  uint32_t rmx_filler[3];
};

struct cfw_rt_msghdr {
  unsigned short rtm_msglen;
  unsigned char rtm_version;
  unsigned char rtm_type;
  unsigned short rtm_index;
  int rtm_flags;
  int rtm_addrs;
  pid_t rtm_pid;
  int rtm_seq;
  int rtm_errno;
  int rtm_use;
  uint32_t rtm_inits;
  struct cfw_rt_metrics rtm_rmx;
};

#define CFW_NET_RT_FLAGS 2
#define CFW_RTF_UP 0x1
#define CFW_RTF_GATEWAY 0x2
#define CFW_RTA_DST 0x1
#define CFW_RTA_GATEWAY 0x2
#define CFW_RTAX_MAX 8
#define CFW_ROUNDUP(a) ((a) > 0 ? (1 + (((a) - 1) | (sizeof(uint32_t) - 1))) : sizeof(uint32_t))

int cfw_default_gateway(unsigned int ifindex, char *buf, size_t len) {
  if (buf == NULL || len == 0) return 0;
  buf[0] = '\0';
  int mib[6] = {CTL_NET, PF_ROUTE, 0, AF_INET, CFW_NET_RT_FLAGS, CFW_RTF_GATEWAY};
  size_t size = 0;
  if (sysctl(mib, 6, NULL, &size, NULL, 0) != 0 || size == 0) return 0;
  // The table can grow between the two calls: leave room.
  size += size / 2 + 512;
  char *table = malloc(size);
  if (table == NULL) return 0;
  int found = 0;
  if (sysctl(mib, 6, table, &size, NULL, 0) == 0) {
    for (char *next = table; next + sizeof(struct cfw_rt_msghdr) <= table + size;) {
      struct cfw_rt_msghdr *rtm = (struct cfw_rt_msghdr *)next;
      if (rtm->rtm_msglen == 0) break;
      char *end = next + rtm->rtm_msglen;
      next = end;
      if (end > table + size) break;
      if ((rtm->rtm_flags & (CFW_RTF_UP | CFW_RTF_GATEWAY)) != (CFW_RTF_UP | CFW_RTF_GATEWAY)) continue;
      if (ifindex != 0 && rtm->rtm_index != ifindex) continue;
      struct sockaddr *addrs[CFW_RTAX_MAX] = {0};
      char *cursor = (char *)(rtm + 1);
      for (int i = 0; i < CFW_RTAX_MAX && cursor < end; i++) {
        if (!(rtm->rtm_addrs & (1 << i))) continue;
        struct sockaddr *sa = (struct sockaddr *)cursor;
        addrs[i] = sa;
        cursor += CFW_ROUNDUP(sa->sa_len);
      }
      struct sockaddr *dst = (rtm->rtm_addrs & CFW_RTA_DST) ? addrs[0] : NULL;
      struct sockaddr *gw = (rtm->rtm_addrs & CFW_RTA_GATEWAY) ? addrs[1] : NULL;
      if (dst == NULL || gw == NULL || dst->sa_family != AF_INET || gw->sa_family != AF_INET) continue;
      // The default route: destination 0.0.0.0.
      if (((struct sockaddr_in *)dst)->sin_addr.s_addr != INADDR_ANY) continue;
      if (inet_ntop(AF_INET, &((struct sockaddr_in *)gw)->sin_addr, buf, (socklen_t)len) != NULL) {
        found = 1;
        break;
      }
    }
  }
  free(table);
  return found;
}
