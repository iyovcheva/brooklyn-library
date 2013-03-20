/*
 * Copyright 2013 by Cloudsoft Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brooklyn.entity.messaging.kafka;

import static java.lang.String.format;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.basic.lifecycle.CommonCommands;
import brooklyn.entity.drivers.downloads.DownloadResolver;
import brooklyn.entity.java.JavaSoftwareProcessSshDriver;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.util.MutableMap;
import brooklyn.util.NetworkUtils;

import com.google.common.collect.ImmutableMap;

public class KafkaZookeeperSshDriver extends JavaSoftwareProcessSshDriver implements KafkaZookeeperDriver {

    private static final Logger log = LoggerFactory.getLogger(KafkaZookeeperSshDriver.class);

    private String expandedInstallDir;

    public KafkaZookeeperSshDriver(KafkaZookeeperImpl entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    @Override
    protected String getLogFileLocation() { return getRunDir()+"/console.out"; }

    @Override
    public Integer getZookeeperPort() { return entity.getAttribute(KafkaZookeeper.ZOOKEEPER_PORT); }

    private String getExpandedInstallDir() {
        if (expandedInstallDir == null) throw new IllegalStateException("expandedInstallDir is null; most likely install was not called");
        return expandedInstallDir;
    }

    @Override
    public void install() {
        DownloadResolver resolver = entity.getManagementContext().getEntityDownloadsManager().newDownloader(this);
        List<String> urls = resolver.getTargets();
        String saveAs = resolver.getFilename();
        expandedInstallDir = getInstallDir()+"/"+resolver.getUnpackedDirectoryName(format("kafka-%s-src", getVersion()));

        List<String> commands = new LinkedList<String>();
        commands.addAll(CommonCommands.downloadUrlAs(urls, saveAs));
        commands.add(CommonCommands.INSTALL_TAR);
        commands.add("tar xzfv "+saveAs);
        commands.add("cd "+expandedInstallDir);
        commands.add("./sbt update");
        commands.add("./sbt package");

        newScript(INSTALLING)
                .failOnNonZeroResultCode()
                .body.append(commands)
                .execute();
    }

    @Override
    public void customize() {
        NetworkUtils.checkPortsValid(MutableMap.of("zookeeperPort", getZookeeperPort()));
        newScript(CUSTOMIZING)
                .failOnNonZeroResultCode()
                .body.append(format("cp -R %s/* %s", getExpandedInstallDir(), getRunDir()))
                .execute();

        String serverConfig = entity.getConfig(KafkaZookeeper.ZOOKEEPER_CONFIG_TEMPLATE);
        copyTemplate(serverConfig, "zookeeper.properties");
    }

    @Override
    public void launch() {
        newScript(ImmutableMap.of("usePidFile", getPidFile()), LAUNCHING)
                .failOnNonZeroResultCode()
                .body.append("nohup ./bin/zookeeper-server-start.sh ./zookeeper.properties > console.out 2>&1 &")
                .execute();
    }

    public String getPidFile() { return getRunDir() + "/kafka.pid"; }

    @Override
    public boolean isRunning() {
        return newScript(ImmutableMap.of("usePidFile", getPidFile()), CHECK_RUNNING).execute() == 0;
    }

    @Override
    public void stop() {
        newScript(ImmutableMap.of("usePidFile", false), STOPPING)
                .body.append("ps ax | grep quorum\\.QuorumPeerMain | awk '{print $1}' | xargs kill")
                .body.append("ps ax | grep quorum\\.QuorumPeerMain | awk '{print $1}' | xargs kill -9")
                .execute();
    }

    @Override
    public Map<String, String> getShellEnvironment() {
        Map<String, String> orig = super.getShellEnvironment();
        return MutableMap.<String, String>builder()
                .put("KAFKA_JMX_OPTS", orig.get("JAVA_OPTS"))
                .build();
    }

}