/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.typesafe.config.Config;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase;
import com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType;
import com.yugabyte.yw.commissioner.tasks.params.DetachedNodeTaskParams;
import com.yugabyte.yw.commissioner.tasks.params.INodeTaskParams;
import com.yugabyte.yw.commissioner.tasks.params.NodeAccessTaskParams;
import com.yugabyte.yw.commissioner.tasks.params.NodeTaskParams;
import com.yugabyte.yw.commissioner.tasks.subtasks.AnsibleClusterServerCtl;
import com.yugabyte.yw.commissioner.tasks.subtasks.AnsibleConfigureServers;
import com.yugabyte.yw.commissioner.tasks.subtasks.AnsibleCreateServer;
import com.yugabyte.yw.commissioner.tasks.subtasks.AnsibleDestroyServer;
import com.yugabyte.yw.commissioner.tasks.subtasks.AnsibleSetupServer;
import com.yugabyte.yw.commissioner.tasks.subtasks.ChangeInstanceType;
import com.yugabyte.yw.commissioner.tasks.subtasks.CreateRootVolumes;
import com.yugabyte.yw.commissioner.tasks.subtasks.InstanceActions;
import com.yugabyte.yw.commissioner.tasks.subtasks.PauseServer;
import com.yugabyte.yw.commissioner.tasks.subtasks.RebootServer;
import com.yugabyte.yw.commissioner.tasks.subtasks.ReplaceRootVolume;
import com.yugabyte.yw.commissioner.tasks.subtasks.ResumeServer;
import com.yugabyte.yw.commissioner.tasks.subtasks.RunHooks;
import com.yugabyte.yw.commissioner.tasks.subtasks.TransferXClusterCerts;
import com.yugabyte.yw.commissioner.tasks.subtasks.UpdateMountedDisks;
import com.yugabyte.yw.common.certmgmt.CertConfigType;
import com.yugabyte.yw.common.certmgmt.CertificateHelper;
import com.yugabyte.yw.common.certmgmt.EncryptionInTransitUtil;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.common.gflags.GFlagsUtil;
import com.yugabyte.yw.common.utils.Pair;
import com.yugabyte.yw.forms.CertificateParams;
import com.yugabyte.yw.forms.CertsRotateParams.CertRotationType;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.UserIntent;
import com.yugabyte.yw.forms.UniverseTaskParams;
import com.yugabyte.yw.forms.UpgradeTaskParams;
import com.yugabyte.yw.forms.VMImageUpgradeParams.VmUpgradeTaskType;
import com.yugabyte.yw.models.AccessKey;
import com.yugabyte.yw.models.AccessKey.KeyInfo;
import com.yugabyte.yw.models.CertificateInfo;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.InstanceType;
import com.yugabyte.yw.models.NodeInstance;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.DeviceInfo;
import com.yugabyte.yw.models.helpers.NodeDetails;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;

@Singleton
@Slf4j
public class NodeManager extends DevopsBase {
  static final String BOOT_SCRIPT_PATH = "yb.universe_boot_script";
  static final String BOOT_SCRIPT_TOKEN = "39666ab2-6633-4806-9685-5134321bd0d1";
  static final String BOOT_SCRIPT_COMPLETE =
      "\nsync\necho " + BOOT_SCRIPT_TOKEN + " >/etc/yb-boot-script-complete\n";
  private static final String YB_CLOUD_COMMAND_TYPE = "instance";
  public static final String CERT_LOCATION_NODE = "node";
  public static final String CERT_LOCATION_PLATFORM = "platform";
  private static final List<String> VALID_CONFIGURE_PROCESS_TYPES =
      ImmutableList.of(ServerType.MASTER.name(), ServerType.TSERVER.name());
  static final String SKIP_CERT_VALIDATION = "yb.tls.skip_cert_validation";
  public static final String POSTGRES_MAX_MEM_MB = "yb.dbmem.postgres.max_mem_mb";
  public static final String YBC_NFS_DIRS = "yb.ybc_flags.nfs_dirs";
  public static final String YBC_ENABLE_VERBOSE = "yb.ybc_flags.enable_verbose";
  public static final String YBC_PACKAGE_REGEX = ".+ybc(.*).tar.gz";
  public static final Pattern YBC_PACKAGE_PATTERN = Pattern.compile(YBC_PACKAGE_REGEX);

  public static final Logger LOG = LoggerFactory.getLogger(NodeManager.class);

  @Inject play.Configuration appConfig;

  @Inject RuntimeConfigFactory runtimeConfigFactory;

  @Inject ConfigHelper configHelper;

  @Inject ReleaseManager releaseManager;

  @Override
  protected String getCommandType() {
    return YB_CLOUD_COMMAND_TYPE;
  }

  // We need to define the enum such that the lower case value matches the action.
  public enum NodeCommandType {
    Provision,
    Create,
    Configure,
    CronCheck,
    Destroy,
    List,
    Control,
    Precheck,
    Tags,
    InitYSQL,
    Disk_Update,
    Update_Mounted_Disks,
    Change_Instance_Type,
    Pause,
    Resume,
    Create_Root_Volumes,
    Replace_Root_Volume,
    Delete_Root_Volumes,
    Transfer_XCluster_Certs,
    Verify_Node_SSH_Access,
    Add_Authorized_Key,
    Remove_Authorized_Key,
    Reboot,
    RunHooks,
    Wait_For_SSH
  }

  public enum CertRotateAction {
    APPEND_NEW_ROOT_CERT,
    REMOVE_OLD_ROOT_CERT,
    ROTATE_CERTS,
    UPDATE_CERT_DIRS
  }

  private UserIntent getUserIntentFromParams(NodeTaskParams nodeTaskParam) {
    Universe universe = Universe.getOrBadRequest(nodeTaskParam.universeUUID);
    return getUserIntentFromParams(universe, nodeTaskParam);
  }

  private UserIntent getUserIntentFromParams(Universe universe, NodeTaskParams nodeTaskParam) {
    NodeDetails nodeDetails = universe.getNode(nodeTaskParam.nodeName);
    if (nodeDetails == null) {
      Iterator<NodeDetails> nodeIter = universe.getUniverseDetails().nodeDetailsSet.iterator();
      if (!nodeIter.hasNext()) {
        throw new RuntimeException("No node is found in universe " + universe.name);
      }
      nodeDetails = nodeIter.next();
      LOG.info("Node {} not found, so using {}.", nodeTaskParam.nodeName, nodeDetails.nodeName);
    }
    return universe.getUniverseDetails().getClusterByUuid(nodeDetails.placementUuid).userIntent;
  }

  private List<String> getCloudArgs(NodeTaskParams nodeTaskParam) {
    List<String> command = new ArrayList<>();
    command.add("--zone");
    command.add(nodeTaskParam.getAZ().code);
    UserIntent userIntent = getUserIntentFromParams(nodeTaskParam);

    // Right now for docker we grab the network from application conf.
    if (userIntent.providerType.equals(Common.CloudType.docker)) {
      String networkName = appConfig.getString("yb.docker.network");
      if (networkName == null) {
        throw new RuntimeException("yb.docker.network is not set in application.conf");
      }
      command.add("--network");
      command.add(networkName);
    }

    if (userIntent.providerType.equals(Common.CloudType.onprem)) {
      // Instance may not be present if it is deleted from NodeInstance table after a release
      // action. Node UUID is not available in 2.6.
      Optional<NodeInstance> node = NodeInstance.maybeGetByName(nodeTaskParam.getNodeName());
      command.add("--node_metadata");
      command.add(node.isPresent() ? node.get().getDetailsJson() : "{}");
    }
    return command;
  }

  private List<String> getAccessKeySpecificCommand(NodeTaskParams params, NodeCommandType type) {
    List<String> subCommand = new ArrayList<>();
    if (params.universeUUID == null) {
      throw new RuntimeException("NodeTaskParams missing Universe UUID.");
    }
    Universe universe = Universe.getOrBadRequest(params.universeUUID);
    NodeDetails node = universe.getNode(params.nodeName);
    UserIntent userIntent = getUserIntentFromParams(params);
    final String defaultAccessKeyCode = appConfig.getString("yb.security.default.access.key");

    // TODO: [ENG-1242] we shouldn't be using our keypair, until we fix our VPC to support VPN
    if (userIntent != null && !userIntent.accessKeyCode.equalsIgnoreCase(defaultAccessKeyCode)) {
      AccessKey accessKey =
          AccessKey.getOrBadRequest(params.getProvider().uuid, userIntent.accessKeyCode);
      AccessKey.KeyInfo keyInfo = accessKey.getKeyInfo();
      subCommand.addAll(
          getAccessKeySpecificCommand(
              params,
              type,
              keyInfo,
              userIntent.providerType,
              userIntent.accessKeyCode,
              node.nodeExporterPort));
    }

    return subCommand;
  }

  private List<String> getAccessKeySpecificCommand(
      INodeTaskParams params,
      NodeCommandType type,
      AccessKey.KeyInfo keyInfo,
      Common.CloudType providerType,
      String accessKeyCode,
      int nodeExporterPort) {
    List<String> subCommand = new ArrayList<>();

    if (keyInfo.vaultFile != null) {
      subCommand.add("--vars_file");
      subCommand.add(keyInfo.vaultFile);
      subCommand.add("--vault_password_file");
      subCommand.add(keyInfo.vaultPasswordFile);
    }
    if (keyInfo.privateKey != null) {
      subCommand.add("--private_key_file");
      subCommand.add(keyInfo.privateKey);

      // We only need to include keyPair name for create instance method and if this is aws.
      if ((params instanceof AnsibleCreateServer.Params
              || params instanceof AnsibleSetupServer.Params)
          && providerType.equals(Common.CloudType.aws)
          && type != NodeCommandType.Wait_For_SSH) {
        subCommand.add("--key_pair_name");
        subCommand.add(accessKeyCode);
        // Also we will add the security group information for create
        if (params instanceof AnsibleCreateServer.Params) {
          Region r = params.getRegion();
          String customSecurityGroupId = r.getSecurityGroupId();
          if (customSecurityGroupId != null) {
            subCommand.add("--security_group_id");
            subCommand.add(customSecurityGroupId);
          }
        }
      }
    }
    // security group is only used during Azure create instance method
    if (params instanceof AnsibleCreateServer.Params
        && providerType.equals(Common.CloudType.azu)
        && type != NodeCommandType.Wait_For_SSH) {
      Region r = params.getRegion();
      String customSecurityGroupId = r.getSecurityGroupId();
      if (customSecurityGroupId != null) {
        subCommand.add("--security_group_id");
        subCommand.add(customSecurityGroupId);
      }
    }

    if (params instanceof AnsibleDestroyServer.Params
        && providerType.equals(Common.CloudType.onprem)) {
      subCommand.add("--install_node_exporter");
    }

    subCommand.add("--custom_ssh_port");
    subCommand.add(keyInfo.sshPort.toString());

    // TODO make this global and remove this conditional check
    // to avoid bugs.
    if ((type == NodeCommandType.Provision
            || type == NodeCommandType.Destroy
            || type == NodeCommandType.Create
            || type == NodeCommandType.Disk_Update
            || type == NodeCommandType.Update_Mounted_Disks
            || type == NodeCommandType.Reboot
            || type == NodeCommandType.Change_Instance_Type
            || type == NodeCommandType.Wait_For_SSH)
        && keyInfo.sshUser != null) {
      subCommand.add("--ssh_user");
      subCommand.add(keyInfo.sshUser);
    }

    if ((type == NodeCommandType.Configure) && keyInfo.sshUser != null) {
      // Pass the sudo user on different key, so as to
      // force reinstall the packages as part of configure.
      subCommand.add("--ssh_user_update_packages");
      subCommand.add(keyInfo.sshUser);
    }

    if (type == NodeCommandType.Precheck) {
      subCommand.add("--precheck_type");
      if (keyInfo.skipProvisioning) {
        subCommand.add("configure");
        subCommand.add("--ssh_user");
        subCommand.add("yugabyte");
      } else {
        subCommand.add("provision");
        if (keyInfo.sshUser != null) {
          subCommand.add("--ssh_user");
          subCommand.add(keyInfo.sshUser);
        }
      }

      if (keyInfo.setUpChrony) {
        subCommand.add("--skip_ntp_check");
      }
      if (keyInfo.airGapInstall) {
        subCommand.add("--air_gap");
      }
      if (keyInfo.installNodeExporter) {
        subCommand.add("--install_node_exporter");
      }
    }

    if (params instanceof AnsibleSetupServer.Params) {
      if (keyInfo.airGapInstall) {
        subCommand.add("--air_gap");
      }

      if (keyInfo.installNodeExporter) {
        subCommand.add("--install_node_exporter");
        subCommand.add("--node_exporter_port");
        subCommand.add(Integer.toString(nodeExporterPort));
        subCommand.add("--node_exporter_user");
        subCommand.add(keyInfo.nodeExporterUser);
      }

      if (keyInfo.setUpChrony) {
        subCommand.add("--use_chrony");
        if (keyInfo.ntpServers != null && !keyInfo.ntpServers.isEmpty()) {
          for (String server : keyInfo.ntpServers) {
            subCommand.add("--ntp_server");
            subCommand.add(server);
          }
        }
      }

      // Legacy providers should not be allowed to have no NTP set up. See PLAT 4015
      if (!keyInfo.showSetUpChrony
          && !keyInfo.airGapInstall
          && !((AnsibleSetupServer.Params) params).useTimeSync
          && (providerType.equals(Common.CloudType.aws)
              || providerType.equals(Common.CloudType.gcp)
              || providerType.equals(Common.CloudType.azu))) {
        subCommand.add("--use_chrony");
        List<String> publicServerList =
            Arrays.asList("0.pool.ntp.org", "1.pool.ntp.org", "2.pool.ntp.org", "3.pool.ntp.org");
        for (String server : publicServerList) {
          subCommand.add("--ntp_server");
          subCommand.add(server);
        }
      }
    } else if (params instanceof ChangeInstanceType.Params) {
      if (keyInfo.airGapInstall) {
        subCommand.add("--air_gap");
      }
    }

    return subCommand;
  }

  private List<String> getDeviceArgs(NodeTaskParams params) {
    List<String> args = new ArrayList<>();
    if (params.deviceInfo.numVolumes != null && !params.getProvider().code.equals("onprem")) {
      args.add("--num_volumes");
      args.add(Integer.toString(params.deviceInfo.numVolumes));
    } else if (params.deviceInfo.mountPoints != null) {
      args.add("--mount_points");
      args.add(params.deviceInfo.mountPoints);
    }
    if (params.deviceInfo.volumeSize != null) {
      args.add("--volume_size");
      args.add(Integer.toString(params.deviceInfo.volumeSize));
    }
    return args;
  }

  private String getThirdpartyPackagePath() {
    String packagePath = appConfig.getString("yb.thirdparty.packagePath");
    if (packagePath != null && !packagePath.isEmpty()) {
      File thirdpartyPackagePath = new File(packagePath);
      if (thirdpartyPackagePath.exists() && thirdpartyPackagePath.isDirectory()) {
        return packagePath;
      }
    }

    return null;
  }

  /**
   * Creates certificates if not present. Called from various places like - when node is added to
   * universe
   *
   * @param config
   * @param userIntent
   * @param taskParam
   * @param nodeIP
   * @param ybHomeDir
   * @return
   */
  private List<String> getCertificatePaths(
      Config config,
      UserIntent userIntent,
      AnsibleConfigureServers.Params taskParam,
      String nodeIP,
      String ybHomeDir,
      Map<String, Integer> subjectAltName) {
    return getCertificatePaths(
        config,
        userIntent,
        taskParam,
        EncryptionInTransitUtil.isRootCARequired(taskParam),
        EncryptionInTransitUtil.isClientRootCARequired(taskParam),
        nodeIP,
        ybHomeDir,
        subjectAltName);
  }

  // Return the List of Strings which gives the certificate paths for the specific taskParams
  private List<String> getCertificatePaths(
      Config config,
      UserIntent userIntent,
      AnsibleConfigureServers.Params taskParam,
      boolean isRootCARequired,
      boolean isClientRootCARequired,
      String commonName,
      String ybHomeDir,
      Map<String, Integer> subjectAltName) {
    List<String> subcommandStrings = new ArrayList<>();

    String serverCertFile = String.format("node.%s.crt", commonName);
    String serverKeyFile = String.format("node.%s.key", commonName);

    if (isRootCARequired) {
      subcommandStrings.add("--certs_node_dir");
      subcommandStrings.add(CertificateHelper.getCertsNodeDir(ybHomeDir));

      CertificateInfo rootCert = CertificateInfo.get(taskParam.rootCA);
      if (rootCert == null) {
        throw new RuntimeException("No valid rootCA found for " + taskParam.universeUUID);
      }

      String rootCertPath, serverCertPath, serverKeyPath, certsLocation;

      switch (rootCert.certType) {
        case SelfSigned:
        case HashicorpVault:
          {
            try {
              // Creating a temp directory to save Server Cert and Key from Root for the node
              Path tempStorageDirectory;
              if (rootCert.certType == CertConfigType.SelfSigned) {
                tempStorageDirectory =
                    Files.createTempDirectory(String.format("SelfSigned%s", taskParam.rootCA))
                        .toAbsolutePath();
              } else {
                tempStorageDirectory =
                    Files.createTempDirectory(String.format("Hashicorp%s", taskParam.rootCA))
                        .toAbsolutePath();
              }
              CertificateHelper.createServerCertificate(
                  config,
                  taskParam.rootCA,
                  tempStorageDirectory.toString(),
                  commonName,
                  null,
                  null,
                  serverCertFile,
                  serverKeyFile,
                  subjectAltName);
              rootCertPath = rootCert.certificate;
              serverCertPath = String.format("%s/%s", tempStorageDirectory, serverCertFile);
              serverKeyPath = String.format("%s/%s", tempStorageDirectory, serverKeyFile);
              certsLocation = CERT_LOCATION_PLATFORM;

              if (taskParam.rootAndClientRootCASame && taskParam.enableClientToNodeEncrypt) {
                // These client certs are used for node to postgres communication
                // These are separate from clientRoot certs which are used for server to client
                // communication These are not required anymore as this is not mandatory now and
                // can be removed. The code is still here to maintain backward compatibility
                subcommandStrings.add("--client_cert_path");
                subcommandStrings.add(CertificateHelper.getClientCertFile(taskParam.rootCA));
                subcommandStrings.add("--client_key_path");
                subcommandStrings.add(CertificateHelper.getClientKeyFile(taskParam.rootCA));
              }
            } catch (IOException e) {
              LOG.error(e.getMessage(), e);
              throw new RuntimeException(e);
            }
            break;
          }
        case CustomCertHostPath:
          {
            CertificateParams.CustomCertInfo customCertInfo = rootCert.getCustomCertPathParams();
            rootCertPath = customCertInfo.rootCertPath;
            serverCertPath = customCertInfo.nodeCertPath;
            serverKeyPath = customCertInfo.nodeKeyPath;
            certsLocation = CERT_LOCATION_NODE;
            if (taskParam.rootAndClientRootCASame
                && taskParam.enableClientToNodeEncrypt
                && customCertInfo.clientCertPath != null
                && !customCertInfo.clientCertPath.isEmpty()
                && customCertInfo.clientKeyPath != null
                && !customCertInfo.clientKeyPath.isEmpty()) {
              // These client certs are used for node to postgres communication
              // These are seprate from clientRoot certs which are used for server to client
              // communication These are not required anymore as this is not mandatory now and
              // can be removed
              // The code is still here to mantain backward compatibility
              subcommandStrings.add("--client_cert_path");
              subcommandStrings.add(customCertInfo.clientCertPath);
              subcommandStrings.add("--client_key_path");
              subcommandStrings.add(customCertInfo.clientKeyPath);
            }
            break;
          }
        case CustomServerCert:
          {
            throw new RuntimeException("rootCA cannot be of type CustomServerCert.");
          }
        default:
          {
            throw new RuntimeException("certType should be valid.");
          }
      }

      // These Server Certs are used for TLS Encryption for Node to Node and
      // (in legacy nodes) client to node as well
      subcommandStrings.add("--root_cert_path");
      subcommandStrings.add(rootCertPath);
      subcommandStrings.add("--server_cert_path");
      subcommandStrings.add(serverCertPath);
      subcommandStrings.add("--server_key_path");
      subcommandStrings.add(serverKeyPath);
      subcommandStrings.add("--certs_location");
      subcommandStrings.add(certsLocation);
    }
    if (isClientRootCARequired) {
      subcommandStrings.add("--certs_client_dir");
      subcommandStrings.add(CertificateHelper.getCertsForClientDir(ybHomeDir));

      CertificateInfo clientRootCert = CertificateInfo.get(taskParam.clientRootCA);
      if (clientRootCert == null) {
        throw new RuntimeException("No valid clientRootCA found for " + taskParam.universeUUID);
      }

      String rootCertPath, serverCertPath, serverKeyPath, certsLocation;

      switch (clientRootCert.certType) {
        case SelfSigned:
        case HashicorpVault:
          {
            try {
              // Creating a temp directory to save Server Cert and Key from Root for the node
              Path tempStorageDirectory;
              if (clientRootCert.certType == CertConfigType.SelfSigned) {
                tempStorageDirectory =
                    Files.createTempDirectory(String.format("SelfSigned%s", taskParam.rootCA))
                        .toAbsolutePath();
              } else {
                tempStorageDirectory =
                    Files.createTempDirectory(String.format("Hashicorp%s", taskParam.rootCA))
                        .toAbsolutePath();
              }
              CertificateHelper.createServerCertificate(
                  config,
                  taskParam.clientRootCA,
                  tempStorageDirectory.toString(),
                  commonName,
                  null,
                  null,
                  serverCertFile,
                  serverKeyFile,
                  subjectAltName);
              rootCertPath = clientRootCert.certificate;
              serverCertPath = String.format("%s/%s", tempStorageDirectory, serverCertFile);
              serverKeyPath = String.format("%s/%s", tempStorageDirectory, serverKeyFile);
              certsLocation = CERT_LOCATION_PLATFORM;
            } catch (IOException e) {
              LOG.error(e.getMessage(), e);
              throw new RuntimeException(e);
            }
            break;
          }
        case CustomCertHostPath:
          {
            CertificateParams.CustomCertInfo customCertInfo =
                clientRootCert.getCustomCertPathParams();
            rootCertPath = customCertInfo.rootCertPath;
            serverCertPath = customCertInfo.nodeCertPath;
            serverKeyPath = customCertInfo.nodeKeyPath;
            certsLocation = CERT_LOCATION_NODE;
            break;
          }
        case CustomServerCert:
          {
            CertificateInfo.CustomServerCertInfo customServerCertInfo =
                clientRootCert.getCustomServerCertInfo();
            rootCertPath = clientRootCert.certificate;
            serverCertPath = customServerCertInfo.serverCert;
            serverKeyPath = customServerCertInfo.serverKey;
            certsLocation = CERT_LOCATION_PLATFORM;
            break;
          }
        default:
          {
            throw new RuntimeException("certType should be valid.");
          }
      }

      // These Server Certs are used for TLS Encryption for Client to Node
      subcommandStrings.add("--root_cert_path_client_to_server");
      subcommandStrings.add(rootCertPath);
      subcommandStrings.add("--server_cert_path_client_to_server");
      subcommandStrings.add(serverCertPath);
      subcommandStrings.add("--server_key_path_client_to_server");
      subcommandStrings.add(serverKeyPath);
      subcommandStrings.add("--certs_location_client_to_server");
      subcommandStrings.add(certsLocation);
    }

    SkipCertValidationType skipType = getSkipCertValidationType(config, userIntent, taskParam);
    if (skipType != SkipCertValidationType.NONE) {
      subcommandStrings.add("--skip_cert_validation");
      subcommandStrings.add(skipType.name());
    }

    return subcommandStrings;
  }

  private List<String> getConfigureSubCommand(AnsibleConfigureServers.Params taskParam) {
    Universe universe = Universe.getOrBadRequest(taskParam.universeUUID);
    Config config = runtimeConfigFactory.forUniverse(universe);
    UserIntent userIntent = getUserIntentFromParams(universe, taskParam);
    List<String> subcommand = new ArrayList<>();
    String masterAddresses = universe.getMasterAddresses(false);
    subcommand.add("--master_addresses_for_tserver");
    subcommand.add(masterAddresses);

    if (masterAddresses == null || masterAddresses.isEmpty()) {
      LOG.warn("No valid masters found during configure for {}.", taskParam.universeUUID);
    }

    if (!taskParam.isMasterInShellMode) {
      subcommand.add("--master_addresses_for_master");
      subcommand.add(masterAddresses);
    }

    NodeDetails node = universe.getNode(taskParam.nodeName);
    String ybServerPackage = null, ybcPackage = null, ybcDir = null;
    Map<String, String> ybcFlags = new HashMap<>();
    if (taskParam.ybSoftwareVersion != null) {
      ReleaseManager.ReleaseMetadata releaseMetadata =
          releaseManager.getReleaseByVersion(taskParam.ybSoftwareVersion);
      if (releaseMetadata != null) {
        if (releaseMetadata.s3 != null) {
          subcommand.add("--s3_remote_download");
          ybServerPackage = releaseMetadata.s3.paths.x86_64;
        } else if (releaseMetadata.gcs != null) {
          subcommand.add("--gcs_remote_download");
          ybServerPackage = releaseMetadata.gcs.paths.x86_64;
        } else if (releaseMetadata.http != null) {
          subcommand.add("--http_remote_download");
          ybServerPackage = releaseMetadata.http.paths.x86_64;
          subcommand.add("--http_package_checksum");
          subcommand.add(releaseMetadata.http.paths.x86_64_checksum);
        } else {
          ybServerPackage = releaseMetadata.getFilePath(taskParam.getRegion());
        }
      }
    }

    if (taskParam.enableYbc) {
      if (ybServerPackage == null) {
        throw new RuntimeException(
            "ybServerPackage cannot be null as we require it to fetch"
                + " the osType, archType of ybcServerPackage");
      }
      Pair<String, String> ybcPackageDetails =
          Util.getYbcPackageDetailsFromYbServerPackage(ybServerPackage);
      ReleaseManager.ReleaseMetadata releaseMetadata =
          releaseManager.getYbcReleaseByVersion(
              taskParam.ybcSoftwareVersion,
              ybcPackageDetails.getFirst(),
              ybcPackageDetails.getSecond());
      ybcPackage = releaseMetadata.getFilePath(taskParam.getRegion());
      if (StringUtils.isBlank(ybcPackage)) {
        throw new RuntimeException("Ybc package cannot be empty with ybc enabled");
      }
      Matcher matcher = YBC_PACKAGE_PATTERN.matcher(ybcPackage);
      boolean matches = matcher.matches();
      if (!matches) {
        throw new RuntimeException(
            String.format(
                "Ybc package: %s does not follow the format required: %s",
                ybcPackage, YBC_PACKAGE_REGEX));
      }
      ybcDir = "ybc" + matcher.group(1);
      ybcFlags = GFlagsUtil.getYbcFlags(taskParam);
      boolean enableVerbose =
          runtimeConfigFactory.forUniverse(universe).getBoolean(YBC_ENABLE_VERBOSE);
      if (enableVerbose) {
        ybcFlags.put("v", "1");
      }
      String nfsDirs = runtimeConfigFactory.forUniverse(universe).getString(YBC_NFS_DIRS);
      ybcFlags.put("nfs_dirs", nfsDirs);
    }

    if (!taskParam.itestS3PackagePath.isEmpty()
        && userIntent.providerType.equals(Common.CloudType.aws)) {
      subcommand.add("--itest_s3_package_path");
      subcommand.add(taskParam.itestS3PackagePath);
    }

    // Pass in communication ports
    subcommand.add("--master_http_port");
    subcommand.add(Integer.toString(node.masterHttpPort));
    subcommand.add("--master_rpc_port");
    subcommand.add(Integer.toString(node.masterRpcPort));
    subcommand.add("--tserver_http_port");
    subcommand.add(Integer.toString(node.tserverHttpPort));
    subcommand.add("--tserver_rpc_port");
    subcommand.add(Integer.toString(node.tserverRpcPort));
    subcommand.add("--cql_proxy_rpc_port");
    subcommand.add(Integer.toString(node.yqlServerRpcPort));
    subcommand.add("--redis_proxy_rpc_port");
    subcommand.add(Integer.toString(node.redisServerRpcPort));

    // Custom cluster creation flow with prebuilt AMI for cloud
    if (taskParam.type != UpgradeTaskParams.UpgradeTaskType.Software) {
      maybeAddVMImageCommandArgs(
          universe,
          userIntent.providerType,
          taskParam.vmUpgradeTaskType,
          !taskParam.ignoreUseCustomImageConfig,
          subcommand);
    }

    boolean useHostname =
        universe.getUniverseDetails().getPrimaryCluster().userIntent.useHostname
            || !isIpAddress(node.cloudInfo.private_ip);

    Map<String, Integer> alternateNames = new HashMap<>();
    String commonName = node.cloudInfo.private_ip;
    alternateNames.put(
        node.cloudInfo.private_ip, useHostname ? GeneralName.dNSName : GeneralName.iPAddress);
    if (node.cloudInfo.secondary_private_ip != null
        && !node.cloudInfo.secondary_private_ip.equals("null")) {
      commonName = node.cloudInfo.secondary_private_ip;
      alternateNames.put(node.cloudInfo.secondary_private_ip, GeneralName.iPAddress);
    }

    switch (taskParam.type) {
      case Everything:
        if (ybServerPackage == null) {
          throw new RuntimeException(
              "Unable to fetch yugabyte release for version: " + taskParam.ybSoftwareVersion);
        }
        subcommand.add("--package");
        subcommand.add(ybServerPackage);
        if (taskParam.enableYbc) {
          subcommand.add("--ybc_flags");
          subcommand.add(Json.stringify(Json.toJson(ybcFlags)));
          subcommand.add("--configure_ybc");
          subcommand.add("--ybc_package");
          subcommand.add(ybcPackage);
          subcommand.add("--ybc_dir");
          subcommand.add(ybcDir);
        }
        subcommand.add("--num_releases_to_keep");
        if (config.getBoolean("yb.cloud.enabled")) {
          subcommand.add(
              runtimeConfigFactory
                  .forUniverse(universe)
                  .getString("yb.releases.num_releases_to_keep_cloud"));
        } else {
          subcommand.add(
              runtimeConfigFactory
                  .forUniverse(universe)
                  .getString("yb.releases.num_releases_to_keep_default"));
        }
        if ((taskParam.enableNodeToNodeEncrypt || taskParam.enableClientToNodeEncrypt)) {
          subcommand.addAll(
              getCertificatePaths(
                  config,
                  userIntent,
                  taskParam,
                  commonName,
                  taskParam.getProvider().getYbHome(),
                  alternateNames));
        }
        break;
      case Software:
        {
          if (ybServerPackage == null) {
            throw new RuntimeException(
                "Unable to fetch yugabyte release for version: " + taskParam.ybSoftwareVersion);
          }
          subcommand.add("--package");
          subcommand.add(ybServerPackage);

          String processType = taskParam.getProperty("processType");
          if (processType == null) {
            throw new RuntimeException("Invalid processType: " + processType);
          } else if (processType == ServerType.CONTROLLER.toString()) {
            if (taskParam.enableYbc) {
              subcommand.add("--ybc_flags");
              subcommand.add(Json.stringify(Json.toJson(ybcFlags)));
              subcommand.add("--configure_ybc");
              subcommand.add("--ybc_package");
              subcommand.add(ybcPackage);
              subcommand.add("--ybc_dir");
              subcommand.add(ybcDir);
            }
          } else if (!VALID_CONFIGURE_PROCESS_TYPES.contains(processType)) {
            throw new RuntimeException("Invalid processType: " + processType);
          } else {
            subcommand.add("--yb_process_type");
            subcommand.add(processType.toLowerCase());
          }
          String taskSubType = taskParam.getProperty("taskSubType");
          if (taskSubType == null) {
            throw new RuntimeException("Invalid taskSubType property: " + taskSubType);
          } else if (taskSubType.equals(UpgradeTaskParams.UpgradeTaskSubType.Download.toString())) {
            subcommand.add("--tags");
            subcommand.add("download-software");
          } else if (taskSubType.equals(UpgradeTaskParams.UpgradeTaskSubType.Install.toString())) {
            subcommand.add("--tags");
            subcommand.add("install-software");
          } else if (taskSubType.equals(
              UpgradeTaskParams.UpgradeTaskSubType.YbcInstall.toString())) {
            subcommand.add("--tags");
            subcommand.add("ybc-install");
          }
          subcommand.add("--num_releases_to_keep");
          if (config.getBoolean("yb.cloud.enabled")) {
            subcommand.add(
                runtimeConfigFactory
                    .forUniverse(universe)
                    .getString("yb.releases.num_releases_to_keep_cloud"));
          } else {
            subcommand.add(
                runtimeConfigFactory
                    .forUniverse(universe)
                    .getString("yb.releases.num_releases_to_keep_default"));
          }
        }
        break;
      case GFlags:
        {
          String processType = taskParam.getProperty("processType");
          if (processType == null) {
            throw new RuntimeException("Invalid processType: " + processType);
          } else if (processType == ServerType.CONTROLLER.toString()) {
            if (taskParam.enableYbc) {
              subcommand.add("--ybc_flags");
              subcommand.add(Json.stringify(Json.toJson(ybcFlags)));
              subcommand.add("--configure_ybc");
              subcommand.add("--ybc_package");
              subcommand.add(ybcPackage);
              subcommand.add("--ybc_dir");
              subcommand.add(ybcDir);
            }
          } else if (!VALID_CONFIGURE_PROCESS_TYPES.contains(processType)) {
            throw new RuntimeException("Invalid processType: " + processType);
          } else {
            subcommand.add("--yb_process_type");
            subcommand.add(processType.toLowerCase());
          }

          // TODO: PLAT-2782: certificates are generated 3 times for each node.
          if ((taskParam.enableNodeToNodeEncrypt || taskParam.enableClientToNodeEncrypt)) {
            subcommand.addAll(
                getCertificatePaths(
                    runtimeConfigFactory.forUniverse(universe),
                    userIntent,
                    taskParam,
                    commonName,
                    taskParam.getProvider().getYbHome(),
                    alternateNames));
          }

          Map<String, String> gflags = new TreeMap<>(taskParam.gflags);
          if (!config.getBoolean("yb.cloud.enabled")) {
            Config runtimeConfig = runtimeConfigFactory.forUniverse(universe);
            GFlagsUtil.processUserGFlags(
                node,
                gflags,
                GFlagsUtil.getAllDefaultGFlags(
                    taskParam, universe, getUserIntentFromParams(taskParam), useHostname, config),
                runtimeConfig.getBoolean("yb.gflags.allow_user_override"));
          }
          subcommand.add("--gflags");
          subcommand.add(Json.stringify(Json.toJson(gflags)));

          subcommand.add("--tags");
          subcommand.add("override_gflags");
        }
        break;
      case Certs:
        {
          if (taskParam.certRotateAction == null) {
            throw new RuntimeException("Cert Rotation Action is null.");
          }

          String processType = taskParam.getProperty("processType");
          if (processType == null) {
            throw new RuntimeException("Invalid processType: " + processType);
          } else if (processType == ServerType.CONTROLLER.toString()) {
            if (taskParam.enableYbc) {
              subcommand.add("--ybc_flags");
              subcommand.add(Json.stringify(Json.toJson(ybcFlags)));
              subcommand.add("--configure_ybc");
              subcommand.add("--ybc_package");
              subcommand.add(ybcPackage);
              subcommand.add("--ybc_dir");
              subcommand.add(ybcDir);
              subcommand.add("--tags");
              subcommand.add("override_ybc_gflags");
              break;
            }
          } else if (!VALID_CONFIGURE_PROCESS_TYPES.contains(processType)) {
            throw new RuntimeException("Invalid processType: " + processType);
          } else {
            subcommand.add("--yb_process_type");
            subcommand.add(processType.toLowerCase());
          }

          String ybHomeDir =
              Provider.getOrBadRequest(
                      UUID.fromString(
                          universe.getUniverseDetails().getPrimaryCluster().userIntent.provider))
                  .getYbHome();
          String certsNodeDir = CertificateHelper.getCertsNodeDir(ybHomeDir);
          String certsForClientDir = CertificateHelper.getCertsForClientDir(ybHomeDir);

          subcommand.add("--cert_rotate_action");
          subcommand.add(taskParam.certRotateAction.toString());

          CertificateInfo rootCert = null;
          if (taskParam.rootCA != null) {
            rootCert = CertificateInfo.get(taskParam.rootCA);
          }

          switch (taskParam.certRotateAction) {
            case APPEND_NEW_ROOT_CERT:
            case REMOVE_OLD_ROOT_CERT:
              {
                if (taskParam.rootCARotationType != CertRotationType.RootCert) {
                  throw new RuntimeException(
                      taskParam.certRotateAction
                          + " is needed only when there is rootCA rotation.");
                }
                if (rootCert == null) {
                  throw new RuntimeException("Certificate is null: " + taskParam.rootCA);
                }
                if (rootCert.certType == CertConfigType.CustomServerCert) {
                  throw new RuntimeException(
                      "Root certificate cannot be of type CustomServerCert.");
                }

                String rootCertPath = "";
                String certsLocation = "";
                if (rootCert.certType == CertConfigType.SelfSigned) {
                  rootCertPath = rootCert.certificate;
                  certsLocation = CERT_LOCATION_PLATFORM;
                } else if (rootCert.certType == CertConfigType.CustomCertHostPath) {
                  rootCertPath = rootCert.getCustomCertPathParams().rootCertPath;
                  certsLocation = CERT_LOCATION_NODE;
                } else if (rootCert.certType == CertConfigType.HashicorpVault) {
                  rootCertPath = rootCert.certificate;
                  certsLocation = CERT_LOCATION_PLATFORM;
                }

                subcommand.add("--root_cert_path");
                subcommand.add(rootCertPath);
                subcommand.add("--certs_location");
                subcommand.add(certsLocation);
                subcommand.add("--certs_node_dir");
                subcommand.add(certsNodeDir);
              }
              break;
            case ROTATE_CERTS:
              {
                subcommand.addAll(
                    getCertificatePaths(
                        config,
                        userIntent,
                        taskParam,
                        taskParam.rootCARotationType != CertRotationType.None,
                        taskParam.clientRootCARotationType != CertRotationType.None,
                        commonName,
                        ybHomeDir,
                        alternateNames));
              }
              break;
            case UPDATE_CERT_DIRS:
              {
                Map<String, String> gflags = new TreeMap<>(taskParam.gflags);
                gflags.putAll(
                    filterCertsAndTlsGFlags(
                        taskParam,
                        universe,
                        Arrays.asList(GFlagsUtil.CERTS_DIR, GFlagsUtil.CERTS_FOR_CLIENT_DIR)));
                subcommand.add("--gflags");
                subcommand.add(Json.stringify(Json.toJson(gflags)));
                subcommand.add("--tags");
                subcommand.add("override_gflags");
                break;
              }
          }
        }
        break;
      case ToggleTls:
        {
          String processType = taskParam.getProperty("processType");
          String subType = taskParam.getProperty("taskSubType");

          if (processType == null) {
            throw new RuntimeException("Invalid processType: " + processType);
          } else if (processType == ServerType.CONTROLLER.toString()) {
            if (taskParam.enableYbc) {
              subcommand.add("--ybc_flags");
              subcommand.add(Json.stringify(Json.toJson(ybcFlags)));
              subcommand.add("--configure_ybc");
              subcommand.add("--ybc_package");
              subcommand.add(ybcPackage);
              subcommand.add("--ybc_dir");
              subcommand.add(ybcDir);
              subcommand.add("--tags");
              subcommand.add("override_ybc_gflags");
              break;
            }
          } else if (!VALID_CONFIGURE_PROCESS_TYPES.contains(processType)) {
            throw new RuntimeException("Invalid processType: " + processType);
          } else {
            subcommand.add("--yb_process_type");
            subcommand.add(processType.toLowerCase());
          }

          final List<String> tlsGflagsToReplace =
              Arrays.asList(
                  GFlagsUtil.USE_NODE_TO_NODE_ENCRYPTION,
                  GFlagsUtil.USE_CLIENT_TO_SERVER_ENCRYPTION,
                  GFlagsUtil.ALLOW_INSECURE_CONNECTIONS,
                  GFlagsUtil.CERTS_DIR,
                  GFlagsUtil.CERTS_FOR_CLIENT_DIR);
          String ybHomeDir =
              Provider.getOrBadRequest(
                      UUID.fromString(
                          universe.getUniverseDetails().getPrimaryCluster().userIntent.provider))
                  .getYbHome();

          if (UpgradeTaskParams.UpgradeTaskSubType.CopyCerts.name().equals(subType)) {
            if (taskParam.enableNodeToNodeEncrypt || taskParam.enableClientToNodeEncrypt) {
              subcommand.add("--cert_rotate_action");
              subcommand.add(CertRotateAction.ROTATE_CERTS.toString());
            }
            subcommand.addAll(
                getCertificatePaths(
                    config, userIntent, taskParam, commonName, ybHomeDir, alternateNames));

          } else if (UpgradeTaskParams.UpgradeTaskSubType.Round1GFlagsUpdate.name()
              .equals(subType)) {
            Map<String, String> gflags = new TreeMap<>(taskParam.gflags);
            if (taskParam.nodeToNodeChange > 0) {
              gflags.putAll(filterCertsAndTlsGFlags(taskParam, universe, tlsGflagsToReplace));
              gflags.put(GFlagsUtil.ALLOW_INSECURE_CONNECTIONS, "true");
            } else if (taskParam.nodeToNodeChange < 0) {
              gflags.put(GFlagsUtil.ALLOW_INSECURE_CONNECTIONS, "true");
            } else {
              gflags.putAll(filterCertsAndTlsGFlags(taskParam, universe, tlsGflagsToReplace));
            }

            subcommand.add("--gflags");
            subcommand.add(Json.stringify(Json.toJson(gflags)));

            subcommand.add("--tags");
            subcommand.add("override_gflags");

          } else if (UpgradeTaskParams.UpgradeTaskSubType.Round2GFlagsUpdate.name()
              .equals(subType)) {
            Map<String, String> gflags = new TreeMap<>(taskParam.gflags);
            if (taskParam.nodeToNodeChange > 0) {
              gflags.putAll(
                  filterCertsAndTlsGFlags(
                      taskParam,
                      universe,
                      Collections.singletonList(GFlagsUtil.ALLOW_INSECURE_CONNECTIONS)));
            } else if (taskParam.nodeToNodeChange < 0) {
              gflags.putAll(filterCertsAndTlsGFlags(taskParam, universe, tlsGflagsToReplace));
            } else {
              LOG.warn("Round2 upgrade not required when there is no change in node-to-node");
            }
            subcommand.add("--gflags");
            subcommand.add(Json.stringify(Json.toJson(gflags)));

            subcommand.add("--tags");
            subcommand.add("override_gflags");
          } else {
            throw new RuntimeException("Invalid taskSubType property: " + subType);
          }
        }
        break;
    }

    // extra_gflags is the base set of gflags that is common to all tasks.
    // These can be overriden by  gflags which contain task-specific overrides.
    // User set flags are added to gflags, so if user specifies any of the gflags set here, they
    // will take precedence over our base set.
    subcommand.add("--extra_gflags");
    subcommand.add(
        Json.stringify(
            Json.toJson(
                GFlagsUtil.getAllDefaultGFlags(
                    taskParam,
                    universe,
                    getUserIntentFromParams(taskParam),
                    useHostname,
                    config))));
    return subcommand;
  }

  private static Map<String, String> filterCertsAndTlsGFlags(
      AnsibleConfigureServers.Params taskParam, Universe universe, List<String> flags) {
    Map<String, String> result =
        new HashMap<>(GFlagsUtil.getCertsAndTlsGFlags(taskParam, universe));
    result.keySet().retainAll(flags);
    return result;
  }

  public static boolean isIpAddress(String maybeIp) {
    InetAddressValidator ipValidator = InetAddressValidator.getInstance();
    return ipValidator.isValidInet4Address(maybeIp) || ipValidator.isValidInet6Address(maybeIp);
  }

  enum SkipCertValidationType {
    ALL,
    HOSTNAME,
    NONE
  }

  @VisibleForTesting
  static SkipCertValidationType getSkipCertValidationType(
      Config config, UserIntent userIntent, AnsibleConfigureServers.Params taskParam) {
    return getSkipCertValidationType(
        config, userIntent, taskParam.gflags, taskParam.gflagsToRemove);
  }

  private static SkipCertValidationType getSkipCertValidationType(
      Config config,
      UserIntent userIntent,
      Map<String, String> gflagsToAdd,
      Set<String> gflagsToRemove) {
    String configValue = config.getString(SKIP_CERT_VALIDATION);
    if (!configValue.isEmpty()) {
      try {
        return SkipCertValidationType.valueOf(configValue);
      } catch (Exception e) {
        log.error("Incorrect config value {} for {} ", configValue, SKIP_CERT_VALIDATION);
      }
    }
    if (gflagsToRemove.contains(GFlagsUtil.VERIFY_SERVER_ENDPOINT_GFLAG)) {
      return SkipCertValidationType.NONE;
    }

    boolean skipHostValidation;
    if (gflagsToAdd.containsKey(GFlagsUtil.VERIFY_SERVER_ENDPOINT_GFLAG)) {
      skipHostValidation = GFlagsUtil.shouldSkipServerEndpointVerification(gflagsToAdd);
    } else {
      skipHostValidation =
          GFlagsUtil.shouldSkipServerEndpointVerification(userIntent.masterGFlags)
              || GFlagsUtil.shouldSkipServerEndpointVerification(userIntent.tserverGFlags);
    }
    return skipHostValidation ? SkipCertValidationType.HOSTNAME : SkipCertValidationType.NONE;
  }

  private Map<String, String> getAnsibleEnvVars(UUID universeUUID) {
    Map<String, String> envVars = new HashMap<>();
    Universe universe = Universe.getOrBadRequest(universeUUID);
    Config runtimeConfig = runtimeConfigFactory.forUniverse(universe);

    envVars.put("ANSIBLE_STRATEGY", runtimeConfig.getString("yb.ansible.strategy"));
    envVars.put(
        "ANSIBLE_TIMEOUT", Integer.toString(runtimeConfig.getInt("yb.ansible.conn_timeout_secs")));
    envVars.put(
        "ANSIBLE_VERBOSITY", Integer.toString(runtimeConfig.getInt("yb.ansible.verbosity")));
    if (runtimeConfig.getBoolean("yb.ansible.debug")) {
      envVars.put("ANSIBLE_DEBUG", "True");
    }
    if (runtimeConfig.getBoolean("yb.ansible.diff_always")) {
      envVars.put("ANSIBLE_DIFF_ALWAYS", "True");
    }
    envVars.put("ANSIBLE_LOCAL_TEMP", runtimeConfig.getString("yb.ansible.local_temp"));

    LOG.trace("ansible env vars {}", envVars);
    return envVars;
  }

  public ShellResponse detachedNodeCommand(
      NodeCommandType type, DetachedNodeTaskParams nodeTaskParam) {
    List<String> commandArgs = new ArrayList<>();
    if (type != NodeCommandType.Precheck) {
      throw new UnsupportedOperationException("Not supported " + type);
    }
    Provider provider = nodeTaskParam.getProvider();
    List<AccessKey> accessKeys = AccessKey.getAll(provider.uuid);
    if (accessKeys.isEmpty()) {
      throw new RuntimeException("No access keys for provider: " + provider.uuid);
    }
    AccessKey accessKey = accessKeys.get(0);
    AccessKey.KeyInfo keyInfo = accessKey.getKeyInfo();
    commandArgs.addAll(
        getAccessKeySpecificCommand(
            nodeTaskParam,
            type,
            keyInfo,
            Common.CloudType.onprem,
            accessKey.getKeyCode(),
            keyInfo.nodeExporterPort));
    commandArgs.addAll(
        getCommunicationPortsParams(
            new UserIntent(), accessKey, new UniverseTaskParams.CommunicationPorts()));

    InstanceType instanceType = InstanceType.get(provider.uuid, nodeTaskParam.getInstanceType());
    commandArgs.add("--mount_points");
    commandArgs.add(instanceType.instanceTypeDetails.volumeDetailsList.get(0).mountPath);

    commandArgs.add(nodeTaskParam.getNodeName());

    NodeInstance nodeInstance = NodeInstance.getOrBadRequest(nodeTaskParam.getNodeUuid());
    JsonNode nodeDetails = Json.toJson(nodeInstance.getDetails());
    ((ObjectNode) nodeDetails).put("nodeName", DetachedNodeTaskParams.DEFAULT_NODE_NAME);

    List<String> cloudArgs = Arrays.asList("--node_metadata", Json.stringify(nodeDetails));

    return execCommand(
        nodeTaskParam.getRegion().uuid,
        null,
        null,
        type.toString().toLowerCase(),
        commandArgs,
        cloudArgs,
        Collections.emptyMap());
  }

  private Path addBootscript(
      Config config, List<String> commandArgs, NodeTaskParams nodeTaskParam) {
    Path bootScriptFile = null;
    String bootScript = config.getString(BOOT_SCRIPT_PATH);
    commandArgs.add("--boot_script");

    // treat the contents as script body if it starts with a shebang line
    // otherwise consider the contents to be a path
    if (bootScript.startsWith("#!")) {
      try {
        bootScriptFile = Files.createTempFile(nodeTaskParam.nodeName, "-boot.sh");
        Files.write(bootScriptFile, bootScript.getBytes());
        Files.write(bootScriptFile, BOOT_SCRIPT_COMPLETE.getBytes(), StandardOpenOption.APPEND);

      } catch (IOException e) {
        LOG.error(e.getMessage(), e);
        throw new RuntimeException(e);
      }
    } else {
      try {
        bootScriptFile = Files.createTempFile(nodeTaskParam.nodeName, "-boot.sh");
        Files.write(bootScriptFile, Files.readAllBytes(Paths.get(bootScript)));
        Files.write(bootScriptFile, BOOT_SCRIPT_COMPLETE.getBytes(), StandardOpenOption.APPEND);

      } catch (IOException e) {
        LOG.error(e.getMessage(), e);
        throw new RuntimeException(e);
      }
    }
    commandArgs.add(bootScriptFile.toAbsolutePath().toString());
    commandArgs.add("--boot_script_token");
    commandArgs.add(BOOT_SCRIPT_TOKEN);
    return bootScriptFile;
  }

  private void addInstanceTags(
      Universe universe,
      UserIntent userIntent,
      NodeTaskParams nodeTaskParam,
      List<String> commandArgs) {
    if (Provider.InstanceTagsEnabledProviders.contains(userIntent.providerType)) {
      addInstanceTags(
          universe, userIntent.instanceTags, userIntent.providerType, nodeTaskParam, commandArgs);
    }
  }

  private void addInstanceTags(
      Universe universe,
      Map<String, String> instanceTags,
      Common.CloudType providerType,
      NodeTaskParams nodeTaskParam,
      List<String> commandArgs) {
    // Create an ordered shallow copy of the tags.
    Map<String, String> useTags = new TreeMap<>(instanceTags);
    filterInstanceTags(useTags, providerType);
    addAdditionalInstanceTags(universe, nodeTaskParam, useTags);
    if (!useTags.isEmpty()) {
      commandArgs.add("--instance_tags");
      commandArgs.add(Json.stringify(Json.toJson(useTags)));
    }
  }

  /**
   * Remove tags that are restricted by provider.
   *
   * @param instanceTags
   * @param providerType
   */
  private void filterInstanceTags(Map<String, String> instanceTags, Common.CloudType providerType) {
    if (providerType.equals(Common.CloudType.aws)) {
      // Do not allow users to overwrite the node name. Only AWS uses tags to set it.
      instanceTags.remove(UniverseDefinitionTaskBase.NODE_NAME_KEY);
    }
  }

  public ShellResponse nodeCommand(NodeCommandType type, NodeTaskParams nodeTaskParam) {
    Universe universe = Universe.getOrBadRequest(nodeTaskParam.universeUUID);
    populateNodeUuidFromUniverse(universe, nodeTaskParam);
    List<String> commandArgs = new ArrayList<>();
    UserIntent userIntent = getUserIntentFromParams(nodeTaskParam);
    Path bootScriptFile = null;
    Map<String, String> sensitiveData = new HashMap<>();
    switch (type) {
      case Replace_Root_Volume:
        if (!(nodeTaskParam instanceof ReplaceRootVolume.Params)) {
          throw new RuntimeException("NodeTaskParams is not ReplaceRootVolume.Params");
        }

        ReplaceRootVolume.Params rrvParams = (ReplaceRootVolume.Params) nodeTaskParam;
        commandArgs.add("--replacement_disk");
        commandArgs.add(rrvParams.replacementDisk);
        commandArgs.addAll(getAccessKeySpecificCommand(rrvParams, type));
        break;
      case Create_Root_Volumes:
        if (!(nodeTaskParam instanceof CreateRootVolumes.Params)) {
          throw new RuntimeException("NodeTaskParams is not CreateRootVolumes.Params");
        }

        CreateRootVolumes.Params crvParams = (CreateRootVolumes.Params) nodeTaskParam;
        commandArgs.add("--num_disks");
        commandArgs.add(String.valueOf(crvParams.numVolumes));
        // intentional fall-thru
      case Create:
        {
          if (!(nodeTaskParam instanceof AnsibleCreateServer.Params)) {
            throw new RuntimeException("NodeTaskParams is not AnsibleCreateServer.Params");
          }
          Config config = this.runtimeConfigFactory.forProvider(nodeTaskParam.getProvider());
          AnsibleCreateServer.Params taskParam = (AnsibleCreateServer.Params) nodeTaskParam;
          Common.CloudType cloudType = userIntent.providerType;
          if (!cloudType.equals(Common.CloudType.onprem)) {
            commandArgs.add("--instance_type");
            commandArgs.add(taskParam.instanceType);
            commandArgs.add("--cloud_subnet");
            commandArgs.add(taskParam.subnetId);

            // Only create second NIC for cloud.
            if (config.getBoolean("yb.cloud.enabled") && taskParam.secondarySubnetId != null) {
              commandArgs.add("--cloud_subnet_secondary");
              commandArgs.add(taskParam.secondarySubnetId);
            }

            // Use case: cloud free tier instances.
            if (config.getBoolean("yb.cloud.enabled")) {
              // If low mem instance, configure small boot disk size.
              if (isLowMemInstanceType(taskParam.instanceType)) {
                String lowMemBootDiskSizeGB = "8";
                LOG.info(
                    "Detected low memory instance type. "
                        + "Setting up nodes using low boot disk size.");
                commandArgs.add("--boot_disk_size_gb");
                commandArgs.add(lowMemBootDiskSizeGB);
              }
            }

            if (config.hasPath(BOOT_SCRIPT_PATH)) {
              bootScriptFile = addBootscript(config, commandArgs, nodeTaskParam);
            }

            // For now we wouldn't add machine image for aws and fallback on the default
            // one devops gives us, we need to transition to having this use versioning
            // like base_image_version [ENG-1859]
            String ybImage =
                Optional.ofNullable(taskParam.machineImage).orElse(taskParam.getRegion().ybImage);
            if (ybImage != null && !ybImage.isEmpty()) {
              commandArgs.add("--machine_image");
              commandArgs.add(ybImage);
            }
            /*
            // TODO(bogdan): talk to Ram about this, if we want/use it for kube/onprem?
            if (!cloudType.equals(Common.CloudType.aws) && !cloudType.equals(Common.CloudType.gcp)) {
              commandArgs.add("--machine_image");
              commandArgs.add(taskParam.getRegion().ybImage);
            }
            */
            if (taskParam.assignPublicIP) {
              commandArgs.add("--assign_public_ip");
            }
            if (config.getBoolean("yb.cloud.enabled")
                && taskParam.assignPublicIP
                && taskParam.assignStaticPublicIP) {
              commandArgs.add("--assign_static_public_ip");
            }
          }
          addInstanceTags(universe, userIntent, nodeTaskParam, commandArgs);
          if (cloudType.equals(Common.CloudType.aws)) {
            if (taskParam.cmkArn != null) {
              commandArgs.add("--cmk_res_name");
              commandArgs.add(taskParam.cmkArn);
            }

            if (taskParam.ipArnString != null) {
              commandArgs.add("--iam_profile_arn");
              commandArgs.add(taskParam.ipArnString);
            }
          }

          if (cloudType.equals(Common.CloudType.azu)) {
            Region r = taskParam.getRegion();
            String vnetName = r.getVnetName();
            if (vnetName != null && !vnetName.isEmpty()) {
              commandArgs.add("--vpcId");
              commandArgs.add(vnetName);
            }
          }

          commandArgs.addAll(getAccessKeySpecificCommand(taskParam, type));
          if (nodeTaskParam.deviceInfo != null) {
            commandArgs.addAll(getDeviceArgs(nodeTaskParam));
            DeviceInfo deviceInfo = nodeTaskParam.deviceInfo;
            if (deviceInfo.storageType != null) {
              commandArgs.add("--volume_type");
              commandArgs.add(deviceInfo.storageType.toString().toLowerCase());
              if (deviceInfo.storageType.isIopsProvisioning() && deviceInfo.diskIops != null) {
                commandArgs.add("--disk_iops");
                commandArgs.add(Integer.toString(deviceInfo.diskIops));
              }
              if (deviceInfo.storageType.isThroughputProvisioning()
                  && deviceInfo.throughput != null) {

                commandArgs.add("--disk_throughput");
                commandArgs.add(Integer.toString(deviceInfo.throughput));
              }
            }
          }
          if (type == NodeCommandType.Create) {
            commandArgs.add("--as_json");
          }
          break;
        }
      case Provision:
        {
          if (!(nodeTaskParam instanceof AnsibleSetupServer.Params)) {
            throw new RuntimeException("NodeTaskParams is not AnsibleSetupServer.Params");
          }
          AnsibleSetupServer.Params taskParam = (AnsibleSetupServer.Params) nodeTaskParam;
          Common.CloudType cloudType = userIntent.providerType;

          if (cloudType.equals(Common.CloudType.aws)) {
            // aws uses instance_type to determine device names for mounting
            commandArgs.add("--instance_type");
            commandArgs.add(taskParam.instanceType);
          }

          // gcp uses machine_image for ansible preprovision.yml
          if (cloudType.equals(Common.CloudType.gcp)) {
            String ybImage =
                Optional.ofNullable(taskParam.machineImage).orElse(taskParam.getRegion().ybImage);
            if (ybImage != null && !ybImage.isEmpty()) {
              commandArgs.add("--machine_image");
              commandArgs.add(ybImage);
            }
          }

          maybeAddVMImageCommandArgs(
              universe,
              cloudType,
              taskParam.vmUpgradeTaskType,
              !taskParam.ignoreUseCustomImageConfig,
              commandArgs);

          if (taskParam.isSystemdUpgrade) {
            // Cron to Systemd Upgrade
            commandArgs.add("--skip_preprovision");
            commandArgs.add("--tags");
            commandArgs.add("systemd_upgrade");
            commandArgs.add("--systemd_services");
          } else if (taskParam.useSystemd) {
            // Systemd for new universes
            commandArgs.add("--systemd_services");
          }

          if (taskParam.useTimeSync
              && (cloudType.equals(Common.CloudType.aws)
                  || cloudType.equals(Common.CloudType.gcp)
                  || cloudType.equals(Common.CloudType.azu))) {
            commandArgs.add("--use_chrony");
          }

          if (cloudType.equals(Common.CloudType.aws)) {
            if (!taskParam.remotePackagePath.isEmpty()) {
              commandArgs.add("--remote_package_path");
              commandArgs.add(taskParam.remotePackagePath);
            }
          }

          Config config = this.runtimeConfigFactory.forProvider(nodeTaskParam.getProvider());
          if (config.hasPath(BOOT_SCRIPT_PATH)) {
            bootScriptFile = addBootscript(config, commandArgs, nodeTaskParam);
          }

          commandArgs.addAll(getAccessKeySpecificCommand(taskParam, type));
          if (nodeTaskParam.deviceInfo != null) {
            commandArgs.addAll(getDeviceArgs(nodeTaskParam));
            DeviceInfo deviceInfo = nodeTaskParam.deviceInfo;
            // Need volume_type in GCP provision to determine correct device names for mounting
            if (deviceInfo.storageType != null && cloudType.equals(Common.CloudType.gcp)) {
              commandArgs.add("--volume_type");
              commandArgs.add(deviceInfo.storageType.toString().toLowerCase());
            }
          }

          String localPackagePath = getThirdpartyPackagePath();
          if (localPackagePath != null) {
            commandArgs.add("--local_package_path");
            commandArgs.add(localPackagePath);
          }

          commandArgs.add("--pg_max_mem_mb");
          commandArgs.add(
              Integer.toString(
                  runtimeConfigFactory.forUniverse(universe).getInt(POSTGRES_MAX_MEM_MB)));

          if (cloudType.equals(Common.CloudType.azu)) {
            NodeDetails node = universe.getNode(taskParam.nodeName);
            if (node != null && node.cloudInfo.lun_indexes.length > 0) {
              commandArgs.add("--lun_indexes");
              commandArgs.add(StringUtils.join(node.cloudInfo.lun_indexes, ","));
            }
          }
          break;
        }
      case Configure:
        {
          if (!(nodeTaskParam instanceof AnsibleConfigureServers.Params)) {
            throw new RuntimeException("NodeTaskParams is not AnsibleConfigureServers.Params");
          }
          AnsibleConfigureServers.Params taskParam = (AnsibleConfigureServers.Params) nodeTaskParam;
          commandArgs.addAll(getConfigureSubCommand(taskParam));
          if (taskParam.isSystemdUpgrade) {
            // Cron to Systemd Upgrade
            commandArgs.add("--tags");
            commandArgs.add("systemd_upgrade");
            commandArgs.add("--systemd_services");
          } else if (taskParam.useSystemd) {
            // Systemd for new universes
            commandArgs.add("--systemd_services");
          } else if (taskParam.updatePackages) {
            commandArgs.add("--update_packages");
          }
          commandArgs.addAll(getAccessKeySpecificCommand(taskParam, type));
          if (nodeTaskParam.deviceInfo != null) {
            commandArgs.addAll(getDeviceArgs(nodeTaskParam));
          }
          sensitiveData.putAll(getReleaseSensitiveData(taskParam));
          break;
        }
      case List:
        {
          if (userIntent.providerType.equals(Common.CloudType.onprem)) {
            if (nodeTaskParam.deviceInfo != null) {
              commandArgs.addAll(getDeviceArgs(nodeTaskParam));
            }
            commandArgs.addAll(getAccessKeySpecificCommand(nodeTaskParam, type));
          }
          commandArgs.add("--as_json");
          break;
        }
      case Destroy:
        {
          if (!(nodeTaskParam instanceof AnsibleDestroyServer.Params)) {
            throw new RuntimeException("NodeTaskParams is not AnsibleDestroyServer.Params");
          }
          AnsibleDestroyServer.Params taskParam = (AnsibleDestroyServer.Params) nodeTaskParam;
          if (taskParam.nodeUuid == null && Strings.isNullOrEmpty(taskParam.nodeIP)) {
            throw new IllegalArgumentException("At least one of node UUID or IP must be specified");
          }
          commandArgs = addArguments(commandArgs, taskParam.nodeIP, taskParam.instanceType);
          if (taskParam.nodeUuid != null) {
            commandArgs.add("--node_uuid");
            commandArgs.add(taskParam.nodeUuid.toString());
          }
          if (taskParam.deviceInfo != null) {
            commandArgs.addAll(getDeviceArgs(taskParam));
          }
          commandArgs.addAll(getAccessKeySpecificCommand(taskParam, type));
          if (userIntent.assignStaticPublicIP) {
            commandArgs.add("--delete_static_public_ip");
          }
          break;
        }
      case Pause:
        {
          if (!(nodeTaskParam instanceof PauseServer.Params)) {
            throw new RuntimeException("NodeTaskParams is not PauseServer.Params");
          }
          PauseServer.Params taskParam = (PauseServer.Params) nodeTaskParam;
          commandArgs = addArguments(commandArgs, taskParam.nodeIP, taskParam.instanceType);
          if (taskParam.deviceInfo != null) {
            commandArgs.addAll(getDeviceArgs(taskParam));
          }
          commandArgs.addAll(getAccessKeySpecificCommand(taskParam, type));
          break;
        }
      case Resume:
        {
          if (!(nodeTaskParam instanceof ResumeServer.Params)) {
            throw new RuntimeException("NodeTaskParams is not ResumeServer.Params");
          }
          ResumeServer.Params taskParam = (ResumeServer.Params) nodeTaskParam;
          commandArgs = addArguments(commandArgs, taskParam.nodeIP, taskParam.instanceType);
          if (taskParam.deviceInfo != null) {
            commandArgs.addAll(getDeviceArgs(taskParam));
          }
          commandArgs.addAll(getAccessKeySpecificCommand(taskParam, type));
          break;
        }
      case Control:
        {
          if (!(nodeTaskParam instanceof AnsibleClusterServerCtl.Params)) {
            throw new RuntimeException("NodeTaskParams is not AnsibleClusterServerCtl.Params");
          }
          AnsibleClusterServerCtl.Params taskParam = (AnsibleClusterServerCtl.Params) nodeTaskParam;
          commandArgs.add(taskParam.process);
          commandArgs.add(taskParam.command);

          // Systemd vs Cron Option
          if (taskParam.useSystemd) {
            commandArgs.add("--systemd_services");
          }
          if (taskParam.checkVolumesAttached) {
            UniverseDefinitionTaskParams.Cluster cluster =
                universe.getCluster(taskParam.placementUuid);
            NodeDetails node = universe.getNode(taskParam.nodeName);
            if (node != null
                && cluster != null
                && cluster.userIntent.getDeviceInfoForNode(node) != null
                && cluster.userIntent.providerType != Common.CloudType.onprem) {
              commandArgs.add("--num_volumes");
              commandArgs.add(
                  String.valueOf(cluster.userIntent.getDeviceInfoForNode(node).numVolumes));
            }
          }
          commandArgs.addAll(getAccessKeySpecificCommand(taskParam, type));
          break;
        }
      case Tags:
        {
          if (!(nodeTaskParam instanceof InstanceActions.Params)) {
            throw new RuntimeException("NodeTaskParams is not InstanceActions.Params");
          }
          InstanceActions.Params taskParam = (InstanceActions.Params) nodeTaskParam;
          if (Provider.InstanceTagsEnabledProviders.contains(userIntent.providerType)) {
            Map<String, String> tags =
                taskParam.tags != null ? taskParam.tags : userIntent.instanceTags;
            if (MapUtils.isEmpty(tags) && taskParam.deleteTags.isEmpty()) {
              throw new RuntimeException("Invalid params: no tags to add or remove");
            }
            addInstanceTags(universe, tags, userIntent.providerType, nodeTaskParam, commandArgs);
            if (!taskParam.deleteTags.isEmpty()) {
              commandArgs.add("--remove_tags");
              commandArgs.add(taskParam.deleteTags);
            }
            if (userIntent.providerType.equals(Common.CloudType.azu)) {
              commandArgs.addAll(getDeviceArgs(taskParam));
              commandArgs.addAll(getAccessKeySpecificCommand(taskParam, type));
            }
          } else {
            throw new IllegalArgumentException(
                "Tags are unsupported for " + userIntent.providerType);
          }
          break;
        }
      case Disk_Update:
        {
          if (!(nodeTaskParam instanceof InstanceActions.Params)) {
            throw new RuntimeException("NodeTaskParams is not InstanceActions.Params");
          }
          InstanceActions.Params taskParam = (InstanceActions.Params) nodeTaskParam;
          commandArgs.addAll(getAccessKeySpecificCommand(taskParam, type));
          commandArgs.add("--instance_type");
          commandArgs.add(taskParam.instanceType);
          if (taskParam.deviceInfo != null) {
            commandArgs.addAll(getDeviceArgs(taskParam));
          }
          break;
        }
      case Update_Mounted_Disks:
        {
          if (!(nodeTaskParam instanceof UpdateMountedDisks.Params)) {
            throw new RuntimeException("NodeTaskParams is not UpdateMountedDisksTask.Params");
          }
          UpdateMountedDisks.Params taskParam = (UpdateMountedDisks.Params) nodeTaskParam;
          commandArgs.add("--instance_type");
          commandArgs.add(taskParam.instanceType);
          if (nodeTaskParam.deviceInfo != null) {
            commandArgs.add("--volume_type");
            commandArgs.add(nodeTaskParam.deviceInfo.storageType.toString().toLowerCase());
            commandArgs.addAll(getDeviceArgs(nodeTaskParam));
          }
          commandArgs.addAll(getAccessKeySpecificCommand(taskParam, type));
          break;
        }
      case Change_Instance_Type:
        {
          if (!(nodeTaskParam instanceof ChangeInstanceType.Params)) {
            throw new RuntimeException("NodeTaskParams is not ResizeNode.Params");
          }
          ChangeInstanceType.Params taskParam = (ChangeInstanceType.Params) nodeTaskParam;
          commandArgs.add("--instance_type");
          commandArgs.add(taskParam.instanceType);

          commandArgs.add("--pg_max_mem_mb");
          commandArgs.add(
              Integer.toString(
                  runtimeConfigFactory.forUniverse(universe).getInt(POSTGRES_MAX_MEM_MB)));

          commandArgs.addAll(getAccessKeySpecificCommand(taskParam, type));
          break;
        }
      case Transfer_XCluster_Certs:
        {
          if (!(nodeTaskParam instanceof TransferXClusterCerts.Params)) {
            throw new RuntimeException("NodeTaskParams is not TransferXClusterCerts.Params");
          }
          TransferXClusterCerts.Params taskParam = (TransferXClusterCerts.Params) nodeTaskParam;
          commandArgs.add("--action");
          commandArgs.add(taskParam.action.toString());
          if (taskParam.action == TransferXClusterCerts.Params.Action.COPY) {
            commandArgs.add("--root_cert_path");
            commandArgs.add(taskParam.rootCertPath.toString());
          }
          commandArgs.add("--replication_config_name");
          commandArgs.add(taskParam.replicationGroupName);
          if (taskParam.producerCertsDirOnTarget != null) {
            commandArgs.add("--producer_certs_dir");
            commandArgs.add(taskParam.producerCertsDirOnTarget.toString());
          }
          commandArgs.addAll(getAccessKeySpecificCommand(taskParam, type));
          break;
        }
      case CronCheck:
        {
          if (!(nodeTaskParam instanceof AnsibleConfigureServers.Params)) {
            throw new RuntimeException("NodeTaskParams is not AnsibleConfigureServers.Params");
          }
          commandArgs.addAll(getAccessKeySpecificCommand(nodeTaskParam, type));
          break;
        }
      case Precheck:
        {
          commandArgs.addAll(getAccessKeySpecificCommand(nodeTaskParam, type));
          if (nodeTaskParam.deviceInfo != null) {
            commandArgs.addAll(getDeviceArgs(nodeTaskParam));
          }
          AccessKey accessKey =
              AccessKey.getOrBadRequest(nodeTaskParam.getProvider().uuid, userIntent.accessKeyCode);
          commandArgs.addAll(
              getCommunicationPortsParams(userIntent, accessKey, nodeTaskParam.communicationPorts));

          boolean rootAndClientAreTheSame =
              nodeTaskParam.clientRootCA == null
                  || Objects.equals(nodeTaskParam.rootCA, nodeTaskParam.clientRootCA);
          appendCertPathsToCheck(
              commandArgs,
              nodeTaskParam.rootCA,
              false,
              rootAndClientAreTheSame && userIntent.enableNodeToNodeEncrypt);

          if (!rootAndClientAreTheSame) {
            appendCertPathsToCheck(commandArgs, nodeTaskParam.clientRootCA, true, false);
          }

          Config config = runtimeConfigFactory.forUniverse(universe);

          SkipCertValidationType skipType =
              getSkipCertValidationType(
                  config, userIntent, Collections.emptyMap(), Collections.emptySet());
          if (skipType != SkipCertValidationType.NONE) {
            commandArgs.add("--skip_cert_validation");
            commandArgs.add(skipType.name());
          }

          break;
        }
      case Delete_Root_Volumes:
        {
          addInstanceTags(universe, userIntent, nodeTaskParam, commandArgs);
          break;
        }
      case Verify_Node_SSH_Access:
        {
          if (!(nodeTaskParam instanceof NodeAccessTaskParams)) {
            throw new RuntimeException("NodeTaskParams is not NodeAccessTaskParams");
          }
          log.info("Verifying access to node {}", nodeTaskParam.nodeName);
          NodeAccessTaskParams taskParams = (NodeAccessTaskParams) nodeTaskParam;
          commandArgs.addAll(getNodeSSHCommand(taskParams));
          String newPrivateKeyFilePath = taskParams.taskAccessKey.getKeyInfo().privateKey;
          sensitiveData.put("--new_private_key_file", newPrivateKeyFilePath);
          break;
        }
      case Add_Authorized_Key:
        {
          if (!(nodeTaskParam instanceof NodeAccessTaskParams)) {
            throw new RuntimeException("NodeTaskParams is not NodeAccessTaskParams");
          }
          log.info("Adding a new key to authorized keys of node {}", nodeTaskParam.nodeName);
          NodeAccessTaskParams taskParams = (NodeAccessTaskParams) nodeTaskParam;
          commandArgs.addAll(getNodeSSHCommand(taskParams));
          // for uploaded private key case, public  key content is taken from private key file
          if (taskParams.taskAccessKey.getKeyInfo().publicKey != null) {
            String pubKeyContent = taskParams.taskAccessKey.getPublicKeyContent();
            if (pubKeyContent.equals("")) {
              throw new RuntimeException("Public key content is empty!");
            }
            sensitiveData.put("--public_key_content", pubKeyContent);
          } else {
            sensitiveData.put("--public_key_content", "");
          }
          String newPrivateKeyFilePath = taskParams.taskAccessKey.getKeyInfo().privateKey;
          sensitiveData.put("--new_private_key_file", newPrivateKeyFilePath);
          break;
        }
      case Remove_Authorized_Key:
        {
          if (!(nodeTaskParam instanceof NodeAccessTaskParams)) {
            throw new RuntimeException("NodeTaskParams is not NodeAccessTaskParams");
          }
          log.info("Removing a key from authorized keys of node {}", nodeTaskParam.nodeName);
          NodeAccessTaskParams taskParams = (NodeAccessTaskParams) nodeTaskParam;
          commandArgs.addAll(getNodeSSHCommand(taskParams));
          // for uploaded private key case, public  key content is taken from private key file
          if (taskParams.taskAccessKey.getKeyInfo().publicKey != null) {
            String pubKeyContent = taskParams.taskAccessKey.getPublicKeyContent();
            if (pubKeyContent.equals("")) {
              throw new RuntimeException("Public key content is empty!");
            }
            sensitiveData.put("--public_key_content", pubKeyContent);
          } else {
            sensitiveData.put("--public_key_content", "");
          }
          String oldPrivateKeyFilePath = taskParams.taskAccessKey.getKeyInfo().privateKey;
          sensitiveData.put("--old_private_key_file", oldPrivateKeyFilePath);
          break;
        }
      case Reboot:
        {
          if (!(nodeTaskParam instanceof RebootServer.Params)) {
            throw new RuntimeException("NodeTaskParams is not RebootServer.Params");
          }
          RebootServer.Params taskParam = (RebootServer.Params) nodeTaskParam;
          commandArgs.addAll(getAccessKeySpecificCommand(taskParam, type));

          if (taskParam.useSSH) {
            commandArgs.add("--use_ssh");
          }

          break;
        }
      case RunHooks:
        {
          if (!(nodeTaskParam instanceof RunHooks.Params)) {
            throw new RuntimeException("NodeTaskParams is not RunHooks.Params");
          }
          RunHooks.Params taskParam = (RunHooks.Params) nodeTaskParam;
          commandArgs.add("--execution_lang");
          commandArgs.add(taskParam.hook.executionLang.name());
          commandArgs.add("--trigger");
          commandArgs.add(taskParam.trigger.name());
          commandArgs.add("--hook_path");
          commandArgs.add(taskParam.hookPath);
          commandArgs.add("--parent_task");
          commandArgs.add(taskParam.parentTask);
          if (taskParam.hook.useSudo) commandArgs.add("--use_sudo");
          Map<String, String> runtimeArgs = taskParam.hook.runtimeArgs;
          if (runtimeArgs != null && runtimeArgs.size() != 0) {
            commandArgs.add("--runtime_args");
            commandArgs.add(Json.stringify(Json.toJson(runtimeArgs)));
          }
          commandArgs.addAll(getAccessKeySpecificCommand(nodeTaskParam, type));
          break;
        }
      case Wait_For_SSH:
        {
          log.info("Connecting to node {}", nodeTaskParam.nodeName);
          commandArgs.addAll(getAccessKeySpecificCommand(nodeTaskParam, type));
          break;
        }
    }
    commandArgs.add(nodeTaskParam.nodeName);
    try {
      return execCommand(
          nodeTaskParam.getRegion().uuid,
          null,
          null,
          type.toString().toLowerCase(),
          commandArgs,
          getCloudArgs(nodeTaskParam),
          getAnsibleEnvVars(nodeTaskParam.universeUUID),
          sensitiveData);
    } finally {
      if (bootScriptFile != null) {
        try {
          Files.deleteIfExists(bootScriptFile);
        } catch (IOException e) {
          LOG.error(e.getMessage(), e);
        }
      }
    }
  }

  private void appendCertPathsToCheck(
      List<String> commandArgs, UUID rootCA, boolean isClient, boolean appendClientPaths) {
    if (rootCA == null) {
      return;
    }
    CertificateInfo rootCert = CertificateInfo.get(rootCA);
    // checking only certs with CustomCertHostPath type, CustomServerCert is not used for onprem
    if (rootCert.certType != CertConfigType.CustomCertHostPath) {
      return;
    }
    String suffix = isClient ? "_client_to_server" : "";

    CertificateParams.CustomCertInfo customCertInfo = rootCert.getCustomCertPathParams();

    commandArgs.add(String.format("--root_cert_path%s", suffix));
    commandArgs.add(customCertInfo.rootCertPath);
    commandArgs.add(String.format("--server_cert_path%s", suffix));
    commandArgs.add(customCertInfo.nodeCertPath);
    commandArgs.add(String.format("--server_key_path%s", suffix));
    commandArgs.add(customCertInfo.nodeKeyPath);
    if (appendClientPaths
        && !StringUtils.isEmpty(customCertInfo.clientCertPath)
        && !StringUtils.isEmpty(customCertInfo.clientKeyPath)) {
      commandArgs.add("--client_cert_path");
      commandArgs.add(customCertInfo.clientCertPath);
      commandArgs.add("--client_key_path");
      commandArgs.add(customCertInfo.clientKeyPath);
    }
  }

  private Collection<String> getCommunicationPortsParams(
      UserIntent userIntent, AccessKey accessKey, UniverseTaskParams.CommunicationPorts ports) {
    List<String> result = new ArrayList<>();
    result.add("--master_http_port");
    result.add(Integer.toString(ports.masterHttpPort));
    result.add("--master_rpc_port");
    result.add(Integer.toString(ports.masterRpcPort));
    result.add("--tserver_http_port");
    result.add(Integer.toString(ports.tserverHttpPort));
    result.add("--tserver_rpc_port");
    result.add(Integer.toString(ports.tserverRpcPort));
    if (userIntent.enableYCQL) {
      result.add("--cql_proxy_http_port");
      result.add(Integer.toString(ports.yqlServerHttpPort));
      result.add("--cql_proxy_rpc_port");
      result.add(Integer.toString(ports.yqlServerRpcPort));
    }
    if (userIntent.enableYSQL) {
      result.add("--ysql_proxy_http_port");
      result.add(Integer.toString(ports.ysqlServerHttpPort));
      result.add("--ysql_proxy_rpc_port");
      result.add(Integer.toString(ports.ysqlServerRpcPort));
    }
    if (userIntent.enableYEDIS) {
      result.add("--redis_proxy_http_port");
      result.add(Integer.toString(ports.redisServerHttpPort));
      result.add("--redis_proxy_rpc_port");
      result.add(Integer.toString(ports.redisServerRpcPort));
    }
    if (accessKey.getKeyInfo().installNodeExporter) {
      result.add("--node_exporter_http_port");
      result.add(Integer.toString(ports.nodeExporterPort));
    }
    return result;
  }

  private List<String> addArguments(List<String> commandArgs, String nodeIP, String instanceType) {
    commandArgs.add("--instance_type");
    commandArgs.add(instanceType);
    if (!Strings.isNullOrEmpty(nodeIP)) {
      commandArgs.add("--node_ip");
      commandArgs.add(nodeIP);
    }
    return commandArgs;
  }

  private boolean isLowMemInstanceType(String instanceType) {
    List<String> lowMemInstanceTypePrefixes = ImmutableList.of("t2.", "t3.");
    String instanceTypePrefix = instanceType.split("\\.")[0] + ".";
    return lowMemInstanceTypePrefixes.contains(instanceTypePrefix);
  }

  // Set the nodeUuid in nodeTaskParam if it is not set.
  private void populateNodeUuidFromUniverse(Universe universe, NodeTaskParams nodeTaskParam) {
    if (nodeTaskParam.nodeUuid == null) {
      NodeDetails nodeDetails = universe.getNode(nodeTaskParam.nodeName);
      if (nodeDetails != null) {
        nodeTaskParam.nodeUuid = nodeDetails.nodeUuid;
      }
    }
    if (nodeTaskParam.nodeUuid == null) {
      UserIntent userIntent = getUserIntentFromParams(universe, nodeTaskParam);
      if (!Common.CloudType.onprem.equals(userIntent.providerType)) {
        // This is for backward compatibility where node UUID is not set in the Universe.
        nodeTaskParam.nodeUuid =
            Util.generateNodeUUID(universe.universeUUID, nodeTaskParam.nodeName);
      }
    }
  }

  private void addAdditionalInstanceTags(
      Universe universe, NodeTaskParams nodeTaskParam, Map<String, String> tags) {
    Customer customer = Customer.get(universe.customerId);
    tags.put("customer-uuid", customer.uuid.toString());
    tags.put("universe-uuid", universe.universeUUID.toString());
    tags.put("node-uuid", nodeTaskParam.nodeUuid.toString());
  }

  private Map<String, String> getReleaseSensitiveData(AnsibleConfigureServers.Params taskParam) {
    Map<String, String> data = new HashMap<>();
    if (taskParam.ybSoftwareVersion != null) {
      ReleaseManager.ReleaseMetadata releaseMetadata =
          releaseManager.getReleaseByVersion(taskParam.ybSoftwareVersion);
      if (releaseMetadata != null) {
        if (releaseMetadata.s3 != null) {
          data.put("--aws_access_key", releaseMetadata.s3.accessKeyId);
          data.put("--aws_secret_key", releaseMetadata.s3.secretAccessKey);
        } else if (releaseMetadata.gcs != null) {
          data.put("--gcs_credentials_json", releaseMetadata.gcs.credentialsJson);
        }
      }
    }
    return data;
  }

  private void maybeAddVMImageCommandArgs(
      Universe universe,
      Common.CloudType cloudType,
      VmUpgradeTaskType vmUpgradeTaskType,
      boolean useCustomImageByDefault,
      List<String> commandArgs) {
    if (!cloudType.equals(Common.CloudType.aws) && !cloudType.equals(Common.CloudType.gcp)) {
      return;
    }
    boolean skipTags = false;
    if (vmUpgradeTaskType == VmUpgradeTaskType.None
        && useCustomImageByDefault
        && universe.getConfig().getOrDefault(Universe.USE_CUSTOM_IMAGE, "false").equals("true")) {
      // Default image is custom image.
      skipTags = true;
    } else if (vmUpgradeTaskType == VmUpgradeTaskType.VmUpgradeWithCustomImages) {
      // This is set only if VMUpgrade is invoked.
      // This can also happen for platform only if yb.upgrade.vmImage is true.
      skipTags = true;
    }
    if (skipTags) {
      commandArgs.add("--skip_tags");
      commandArgs.add("yb-prebuilt-ami");
    }
  }

  public List<String> getNodeSSHCommand(NodeAccessTaskParams params) {
    KeyInfo keyInfo = params.accessKey.getKeyInfo();
    Provider provider = Provider.getOrBadRequest(params.customerUUID, params.providerUUID);
    Integer sshPort = keyInfo.sshPort == null ? provider.sshPort : keyInfo.sshPort;
    String sshUser = params.sshUser;
    String vaultPasswordFile = keyInfo.vaultPasswordFile;
    String vaultFile = keyInfo.vaultFile;
    List<String> commandArgs = new ArrayList<>();
    commandArgs.add("--ssh_user");
    commandArgs.add(sshUser);
    commandArgs.add("--custom_ssh_port");
    commandArgs.add(sshPort.toString());
    commandArgs.add("--vault_password_file");
    commandArgs.add(vaultPasswordFile);
    commandArgs.add("--vars_file");
    commandArgs.add(vaultFile);
    String privateKeyFilePath = keyInfo.privateKey;
    if (privateKeyFilePath != null) {
      commandArgs.add("--private_key_file");
      commandArgs.add(privateKeyFilePath);
    } else {
      throw new RuntimeException("No key found at the private key file path!");
    }
    return commandArgs;
  }

  public String getYbServerPackageName(String ybSoftwareVersion, Region region) {
    String ybServerPackage = null;
    ReleaseManager.ReleaseMetadata releaseMetadata =
        releaseManager.getReleaseByVersion(ybSoftwareVersion);
    if (releaseMetadata != null) {
      if (releaseMetadata.s3 != null) {
        ybServerPackage = releaseMetadata.s3.paths.x86_64;
      } else if (releaseMetadata.gcs != null) {
        ybServerPackage = releaseMetadata.gcs.paths.x86_64;
      } else if (releaseMetadata.http != null) {
        ybServerPackage = releaseMetadata.http.paths.x86_64;
      } else {
        ybServerPackage = releaseMetadata.getFilePath(region);
      }
    }
    return ybServerPackage;
  }
}
