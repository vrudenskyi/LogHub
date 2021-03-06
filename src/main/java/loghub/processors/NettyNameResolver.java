package loghub.processors;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutionException;

import javax.cache.Cache;
import javax.cache.processor.EntryProcessor;

import io.netty.channel.AddressedEnvelope;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsPtrRecord;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsNameResolverException;
import io.netty.resolver.dns.SingletonDnsServerAddressStreamProvider;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import loghub.Event;
import loghub.Helpers;
import loghub.ProcessorException;
import loghub.configuration.Properties;

public class NettyNameResolver extends AsyncFieldsProcessor<AddressedEnvelope<DnsResponse,InetSocketAddress>> {

    private static class DnsCacheKey {
        private final String query;
        private final DnsRecordType type;
        public DnsCacheKey(DnsQuestion query) {
            this.query = query.name();
            this.type = query.type();
        }
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((query == null) ? 0 : query.hashCode());
            result = prime * result + ((type == null) ? 0 : type.hashCode());
            return result;
        }
        @Override
        public boolean equals(Object obj) {
            if(this == obj)
                return true;
            if(obj == null)
                return false;
            if(getClass() != obj.getClass())
                return false;
            DnsCacheKey other = (DnsCacheKey) obj;
            if(query == null) {
                if(other.query != null)
                    return false;
            } else if(!query.equals(other.query))
                return false;
            if(type == null) {
                if(other.type != null)
                    return false;
            } else if(!type.equals(other.type))
                return false;
            return true;
        }
        @Override
        public String toString() {
            return "[" + query + " IN " + type.name() + "]";
        }
    }

    private static class DnsCacheEntry {
        final DnsRecord answserRr;
        final DnsQuestion questionRr;
        final DnsResponseCode code;
        final Instant eol;

        DnsCacheEntry(AddressedEnvelope<DnsResponse, InetSocketAddress> enveloppe) {
            answserRr = enveloppe.content().recordAt((DnsSection.ANSWER));
            questionRr = (DnsQuestion) enveloppe.content().recordAt((DnsSection.QUESTION));
            code = enveloppe.content().code();
            // Default to 5s on failure, just to avoid wild loop
            // Also check than the answerRR is not null, some servers are happy to return ok on failure
            int ttl = (code.intValue() == NOERROR && answserRr != null) ? (int) answserRr.timeToLive() : 5;
            eol = Instant.now().plus(ttl, ChronoUnit.SECONDS);
            assert ! (answserRr instanceof ReferenceCounted);
            assert ! (questionRr instanceof ReferenceCounted);
            assert ! (code instanceof ReferenceCounted);
        }

        @Override
        public String toString() {
            return "DnsCacheEntry [questionRr=" + questionRr + ", answserRr=" + answserRr + ", code=" + code + "]";
        }
    }

    private static final int NOERROR = DnsResponseCode.NOERROR.intValue();
    private static final EventLoopGroup evg = new NioEventLoopGroup(1, new DefaultThreadFactory("dnsresolver"));

    private final EntryProcessor<DnsCacheKey, DnsCacheEntry, Object> checkTTL = (i, j) -> {
        if (i.exists() && i.getValue().eol.isBefore(Instant.now())) {
            i.remove();
            return null;
        } else if (i.exists()) {
            return store(i.getValue());
        } else {
            return null;
        }
    };

    private final EntryProcessor<DnsCacheKey, DnsCacheEntry, Object> addEntry = (i, j) -> {
        if (! i.exists()) {
            @SuppressWarnings("unchecked")
            AddressedEnvelope<DnsResponse, InetSocketAddress> enveloppe = (AddressedEnvelope<DnsResponse, InetSocketAddress>) j[0];
            i.setValue(new DnsCacheEntry(enveloppe));
        }
        return store(i.getValue());
    };

    private DnsNameResolver resolver;
    private int cacheSize = 10000;
    private Cache<DnsCacheKey, DnsCacheEntry> hostCache;

    @Override
    public boolean configure(Properties properties) {
        DnsNameResolverBuilder builder = new DnsNameResolverBuilder(evg.next())
                        .queryTimeoutMillis(Math.max(getTimeout() - 1, 1) * 1000L)
                        .channelType(NioDatagramChannel.class)
                        ;
        InetSocketAddress resolverAddr = null;
        try {
            if (getResolver() != null) {
                resolverAddr = new InetSocketAddress(InetAddress.getByName(getResolver()), 53);
                builder = builder.nameServerProvider(new SingletonDnsServerAddressStreamProvider(resolverAddr));
            }
        } catch (UnknownHostException e) {
            logger.error("Unknown resolver '{}': {}", getResolver(), e.getMessage());
            return false;
        }
        resolver = builder.build();

        hostCache = properties.cacheManager.getBuilder(DnsCacheKey.class, DnsCacheEntry.class)
                        .setName("NameResolver", resolverAddr)
                        .setCacheSize(cacheSize)
                        .build();

        return super.configure(properties);
    }

    String resolverName = null;

    @Override
    public Object fieldFunction(Event event, Object addr)
                    throws ProcessorException {
        InetAddress ipaddr = null;
        String toresolv = null;

        // If a string was given, convert it to a Inet?Address
        if(addr instanceof String) {
            try {
                ipaddr = Helpers.parseIpAddres((String) addr);
            } catch (UnknownHostException e) {
                throw event.buildException("invalid IP address '" + addr + "': " + e.getMessage());
            }
        } else if (addr instanceof InetAddress) {
            ipaddr = (InetAddress) addr;
        }
        if(ipaddr instanceof Inet4Address) {
            Inet4Address ipv4 = (Inet4Address) ipaddr;
            byte[] parts = ipv4.getAddress();
            // the & 0xFF is needed because bytes are signed bytes
            toresolv = String.format("%d.%d.%d.%d.in-addr.arpa.", parts[3] & 0xFF , parts[2] & 0xFF , parts[1] & 0xFF, parts[0] & 0xFF);
        } else if(ipaddr instanceof Inet6Address) {
            Inet6Address ipv6 = (Inet6Address) ipaddr;
            byte[] parts = ipv6.getAddress();
            StringBuilder buffer = new StringBuilder();
            for(int i = parts.length - 1; i >= 0; i--) {
                buffer.append(String.format("%x.%x.", parts[i] & 0x0F, (parts[i] & 0xF0) >> 4));
            }
            buffer.append("ip6.arpa");
            toresolv = buffer.toString();
        }

        if (toresolv != null) {
            //If a query was build, use it
            DnsQuestion dnsquery = new DefaultDnsQuestion(toresolv, DnsRecordType.PTR);
            Object found = hostCache.invoke(new DnsCacheKey(dnsquery), checkTTL);
            if (found != null) {
                return found;
            } else {
                Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> future = resolver.query(dnsquery);
                throw new ProcessorException.PausedEventException(event, future);
            }
        } else if(addr instanceof String) {
            // if addr was a String, it's used a a hostname
            return addr;
        } else {
            return false;
        }
    }

    /**
     * @return the the IP of the resolver
     */
    public String getResolver() {
        return resolverName;
    }

    /**
     * @param resolver The IP of the resolver
     */
    public void setResolver(String resolver) {
        this.resolverName = resolver;
    }

    @Override
    public Object asyncProcess(Event ev, AddressedEnvelope<DnsResponse, InetSocketAddress> enveloppe) throws ProcessorException {
        try {
            DnsResponse response = enveloppe.content();
            DnsQuestion questionRr = (DnsQuestion) response.recordAt((DnsSection.QUESTION));
            return hostCache.invoke(new DnsCacheKey(questionRr), addEntry, enveloppe);
        } finally {
            enveloppe.release();
        }
    }

    private Object store(DnsCacheEntry value) {
        if (value.answserRr != null && value.answserRr instanceof DnsPtrRecord) {
            // DNS responses end the query with a ., substring removes it.
            DnsPtrRecord ptr = (DnsPtrRecord) value.answserRr;
            return ptr.hostname().substring(0, ptr.hostname().length() - 1);
        } else {
            return FieldsProcessor.RUNSTATUS.FAILED;
        }
    }

    @Override
    public boolean manageException(Event event, Exception ex, String[]  destination) throws ProcessorException {
        if (ex instanceof DnsNameResolverException) {
            return false;
        } else {
            throw event.buildException("name resolution failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * @return the cacheSize
     */
    public int getCacheSize() {
        return cacheSize;
    }

    /**
     * @param cacheSize the cacheSize to set
     */
    public void setCacheSize(int cacheSize) {
        this.cacheSize = cacheSize;
    }

    /**
     * Used by test to warm up the cache
     * @param query
     * @param type
     * @return
     * @throws Throwable
     */
    DnsRecord warmUp(String query, DnsRecordType type) throws Throwable {
        AddressedEnvelope<DnsResponse, InetSocketAddress> enveloppe = null;
        try {
            DnsQuestion dnsquery = new DefaultDnsQuestion(query, type);
            Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> future = resolver.query(dnsquery);
            enveloppe = future.get();
            hostCache.put(new DnsCacheKey(dnsquery), new DnsCacheEntry(enveloppe));
            return enveloppe.content().recordAt((DnsSection.ANSWER));
        } catch (ExecutionException e) {
            throw e.getCause();
        } finally {
            if (enveloppe != null) {
                enveloppe.release();
            }
        }
    }

}
