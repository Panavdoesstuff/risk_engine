package riskengine.host;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

/** Topic names and the one rule about them: the partition count is fixed for the life of a log. */
public final class Topics {
    public static final String ENGINE_EVENTS = "engine-events";
    public static final String ENGINE_OUT = "engine-out";

    private Topics() {}

    /** Partition count of an existing topic, or -1 if it does not exist. */
    public static int partitionCount(String bootstrap, String topic) {
        try (Admin admin = admin(bootstrap)) {
            TopicDescription d = admin.describeTopics(List.of(topic)).allTopicNames().get(30, TimeUnit.SECONDS).get(topic);
            return d.partitions().size();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) return -1;
            throw new IllegalStateException("cannot describe topic " + topic, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted describing topic " + topic, e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("timed out describing topic " + topic + " at " + bootstrap, e);
        }
    }

    /**
     * Fails unless the topic exists with exactly {@code partitions} partitions. Accounts are routed by
     * hash(accountId) % N, so running against a topic with a different N would send every account's
     * events to a shard that does not own it.
     */
    public static void requirePartitions(String bootstrap, String topic, int partitions) {
        int have = partitionCount(bootstrap, topic);
        if (have < 0) throw new IllegalStateException("topic " + topic + " does not exist (run scripts/create-topics.sh)");
        if (have != partitions) {
            throw new IllegalStateException("topic " + topic + " has " + have + " partitions but " + partitions + " are configured");
        }
    }

    /**
     * Same rules as scripts/create-topics.sh, for tests and local runs: create with infinite retention if
     * missing, refuse if it exists with a different partition count.
     */
    public static void ensure(String bootstrap, String topic, int partitions) {
        if (partitionCount(bootstrap, topic) < 0) {
            try (Admin admin = admin(bootstrap)) {
                NewTopic t = new NewTopic(topic, partitions, (short) 1)
                        .configs(Map.of("cleanup.policy", "delete", "retention.ms", "-1"));
                admin.createTopics(List.of(t)).all().get(30, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                throw new IllegalStateException("cannot create topic " + topic, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted creating topic " + topic, e);
            }
        }
        requirePartitions(bootstrap, topic, partitions);
    }

    private static Admin admin(String bootstrap) {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        return Admin.create(p);
    }
}
