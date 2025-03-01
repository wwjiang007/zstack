package org.zstack.storage.primary.local;

import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.zstack.compute.vm.ImageBackupStorageSelector;
import org.zstack.core.Platform;
import org.zstack.core.asyncbatch.While;
import org.zstack.core.cloudbus.CloudBusCallBack;
import org.zstack.core.cloudbus.CloudBusListCallBack;
import org.zstack.core.cloudbus.MessageSafe;
import org.zstack.core.componentloader.PluginRegistry;
import org.zstack.core.db.Q;
import org.zstack.core.db.SQLBatch;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.thread.AsyncThread;
import org.zstack.core.thread.ChainTask;
import org.zstack.core.thread.SyncTaskChain;
import org.zstack.core.upgrade.GrayVersion;
import org.zstack.core.workflow.FlowChainBuilder;
import org.zstack.core.workflow.ShareFlow;
import org.zstack.core.workflow.SimpleFlowChain;
import org.zstack.header.HasThreadContext;
import org.zstack.header.cluster.ClusterInventory;
import org.zstack.header.core.Completion;
import org.zstack.header.core.NopeCompletion;
import org.zstack.header.core.ReturnValueCompletion;
import org.zstack.header.core.WhileDoneCompletion;
import org.zstack.header.core.progress.TaskProgressRange;
import org.zstack.header.core.validation.Validation;
import org.zstack.header.core.workflow.*;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.errorcode.ErrorCodeList;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.host.*;
import org.zstack.header.image.ImageBackupStorageRefInventory;
import org.zstack.header.image.ImageConstant.ImageMediaType;
import org.zstack.header.image.ImageInventory;
import org.zstack.header.image.ImageStatus;
import org.zstack.header.image.ImageVO;
import org.zstack.header.message.Message;
import org.zstack.header.message.MessageReply;
import org.zstack.header.rest.RESTFacade;
import org.zstack.header.storage.backup.*;
import org.zstack.header.storage.primary.*;
import org.zstack.header.storage.primary.UndoSnapshotCreationOnPrimaryStorageMsg;
import org.zstack.header.storage.snapshot.VolumeSnapshotConstant;
import org.zstack.header.storage.snapshot.VolumeSnapshotInventory;
import org.zstack.header.storage.snapshot.VolumeSnapshotVO;
import org.zstack.header.vm.VmInstanceSpec.ImageSpec;
import org.zstack.header.vm.VmInstanceState;
import org.zstack.header.vm.VmInstanceVO;
import org.zstack.header.vm.VmInstanceVO_;
import org.zstack.header.volume.*;
import org.zstack.identity.AccountManager;
import org.zstack.kvm.*;
import org.zstack.storage.primary.*;
import org.zstack.storage.primary.local.LocalStorageKvmMigrateVmFlow.CopyBitsFromRemoteCmd;
import org.zstack.storage.primary.local.MigrateBitsStruct.ResourceInfo;
import org.zstack.storage.volume.VolumeErrors;
import org.zstack.storage.volume.VolumeSystemTags;
import org.zstack.utils.CollectionUtils;
import org.zstack.utils.DebugUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.function.Function;
import org.zstack.utils.logging.CLogger;
import org.zstack.utils.path.PathUtil;

import javax.persistence.Tuple;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

import static org.zstack.core.Platform.inerr;
import static org.zstack.core.Platform.operr;
import static org.zstack.core.progress.ProgressReportService.*;
import static org.zstack.utils.CollectionDSL.list;

/**
 * Created by frank on 6/30/2015.
 */
public class LocalStorageKvmBackend extends LocalStorageHypervisorBackend {
    private final static CLogger logger = Utils.getLogger(LocalStorageKvmBackend.class);

    @Autowired
    private AccountManager acntMgr;
    @Autowired
    private LocalStorageFactory localStorageFactory;
    @Autowired
    private RESTFacade restf;
    @Autowired
    private PluginRegistry pluginRgty;

    public static class AgentCommand extends KVMAgentCommands.PrimaryStorageCommand {
        @GrayVersion(value = "5.0.0")
        public String uuid;
        @GrayVersion(value = "5.0.0")
        public String storagePath;
    }

    public static class AgentResponse {
        private Long totalCapacity;
        private Long availableCapacity;

        private boolean success = true;
        private String error;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.success = false;
            this.error = error;
        }

        public Long getTotalCapacity() {
            return totalCapacity;
        }

        public void setTotalCapacity(Long totalCapacity) {
            this.totalCapacity = totalCapacity;
        }

        public Long getAvailableCapacity() {
            return availableCapacity;
        }

        public void setAvailableCapacity(Long availableCapacity) {
            this.availableCapacity = availableCapacity;
        }

        protected ErrorCode buildErrorCode() {
            if (success) {
                return null;
            }
            return operr("operation error, because:%s", error);
        }
    }

    public static class InitCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        private String path;
        @GrayVersion(value = "5.0.0")
        private String hostUuid;
        @GrayVersion(value = "5.0.0")
        private String initFilePath;

        public String getHostUuid() {
            return hostUuid;
        }

        public void setHostUuid(String hostUuid) {
            this.hostUuid = hostUuid;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getInitFilePath() {
            return initFilePath;
        }

        public void setInitFilePath(String initFilePath) {
            this.initFilePath = initFilePath;
        }
    }

    public static class InitRsp extends AgentResponse {
        private Long localStorageUsedCapacity;

        public Long getLocalStorageUsedCapacity() {
            return localStorageUsedCapacity;
        }

        public void setLocalStorageUsedCapacity(Long localStorageUsedCapacity) {
            this.localStorageUsedCapacity = localStorageUsedCapacity;
        }
    }

    public static class CreateFolderCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        private String installUrl;

        public String getInstallUrl() {
            return installUrl;
        }

        public void setInstallUrl(String installUrl) {
            this.installUrl = installUrl;
        }
    }

    public static class CreateEmptyVolumeCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        private String installUrl;
        @GrayVersion(value = "5.0.0")
        private long size;
        @GrayVersion(value = "5.0.0")
        private String accountUuid;
        @GrayVersion(value = "5.0.0")
        private String name;
        @GrayVersion(value = "5.0.0")
        private String volumeUuid;
        @GrayVersion(value = "5.0.0")
        private String backingFile;

        public String getBackingFile() {
            return backingFile;
        }

        public void setBackingFile(String backingFile) {
            this.backingFile = backingFile;
        }

        public String getInstallUrl() {
            return installUrl;
        }

        public void setInstallUrl(String installUrl) {
            this.installUrl = installUrl;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        public String getAccountUuid() {
            return accountUuid;
        }

        public void setAccountUuid(String accountUuid) {
            this.accountUuid = accountUuid;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getVolumeUuid() {
            return volumeUuid;
        }

        public void setVolumeUuid(String volumeUuid) {
            this.volumeUuid = volumeUuid;
        }
    }

    public static class CreateEmptyVolumeRsp extends AgentResponse {
        public Long actualSize;
        public Long size;
    }

    public static class GetPhysicalCapacityCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        private String hostUuid;

        public String getHostUuid() {
            return hostUuid;
        }

        public void setHostUuid(String hostUuid) {
            this.hostUuid = hostUuid;
        }
    }

    public static class CreateVolumeFromCacheCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        private String templatePathInCache;
        @GrayVersion(value = "5.0.0")
        private String installUrl;
        @GrayVersion(value = "5.0.0")
        private String volumeUuid;
        @GrayVersion(value = "5.0.0")
        private long virtualSize;

        public String getTemplatePathInCache() {
            return templatePathInCache;
        }

        public void setTemplatePathInCache(String templatePathInCache) {
            this.templatePathInCache = templatePathInCache;
        }

        public String getInstallUrl() {
            return installUrl;
        }

        public void setInstallUrl(String installUrl) {
            this.installUrl = installUrl;
        }

        public String getVolumeUuid() {
            return volumeUuid;
        }

        public void setVolumeUuid(String volumeUuid) {
            this.volumeUuid = volumeUuid;
        }

        public long getVirtualSize() {
            return virtualSize;
        }

        public void setVirtualSize(long virtualSize) {
            this.virtualSize = virtualSize;
        }
    }

    public static class CreateVolumeFromCacheRsp extends AgentResponse {
        public Long actualSize;
        public Long size;
    }

    public static class CreateVolumeWithBackingCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        public String templatePathInCache;
        @GrayVersion(value = "5.0.0")
        public String installPath;
        @GrayVersion(value = "5.0.0")
        public String volumeUuid;
    }

    public static class CreateVolumeWithBackingRsp extends AgentResponse {
        public long actualSize;
        public long size;
    }

    public static class DeleteBitsCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        private String hostUuid;
        @GrayVersion(value = "5.0.0")
        private String path;
        @GrayVersion(value = "5.0.0")
        private String username;

        public String getHostUuid() {
            return hostUuid;
        }

        public void setHostUuid(String hostUuid) {
            this.hostUuid = hostUuid;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }
    }

    public static class DeleteBitsRsp extends AgentResponse {
        public boolean inUse;
        public ErrorCode buildErrorCode() {
            if (inUse) {
                return Platform.err(VolumeErrors.VOLUME_IN_USE, getError());
            }
            return super.buildErrorCode();
        }
    }

    public static class UnlinkBitsCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        public String installPath;
        @GrayVersion(value = "5.0.0")
        public boolean onlyLinkedFile = true;
    }

    public static class UnlinkBitsRsp extends AgentResponse {
    }

    public static class ListPathCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        private String path;
        @GrayVersion(value = "5.0.0")
        private String hostUuid;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getHostUuid() {
            return hostUuid;
        }

        public void setHostUuid(String hostUuid) {
            this.hostUuid = hostUuid;
        }
    }

    public static class ListPathRsp extends AgentResponse {
        private List<String> paths;

        public List<String> getPaths() {
            return paths;
        }

        public void setPaths(List<String> paths) {
            this.paths = paths;
        }
    }

    public static class CreateTemplateFromVolumeCmd extends AgentCommand implements HasThreadContext{
        @GrayVersion(value = "5.0.0")
        private String installPath;
        @GrayVersion(value = "5.0.0")
        private String volumePath;

        public String getInstallPath() {
            return installPath;
        }

        public void setInstallPath(String installPath) {
            this.installPath = installPath;
        }

        public String getVolumePath() {
            return volumePath;
        }

        public void setVolumePath(String rootVolumePath) {
            this.volumePath = rootVolumePath;
        }
    }

    public static class CreateTemplateFromVolumeRsp extends AgentResponse {
        private long size;
        private long actualSize;

        public long getActualSize() {
            return actualSize;
        }

        public void setActualSize(long actualSize) {
            this.actualSize = actualSize;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }
    }

    public static class EstimateTemplateSizeCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        private String volumePath;

        public String getVolumePath() {
            return volumePath;
        }

        public void setVolumePath(String rootVolumePath) {
            this.volumePath = rootVolumePath;
        }
    }

    public static class EstimateTemplateSizeRsp extends AgentResponse {
        private long size;
        private long actualSize;

        public long getActualSize() {
            return actualSize;
        }

        public void setActualSize(long actualSize) {
            this.actualSize = actualSize;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }
    }

    public static class RevertVolumeFromSnapshotCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        private String snapshotInstallPath;

        public String getSnapshotInstallPath() {
            return snapshotInstallPath;
        }

        public void setSnapshotInstallPath(String snapshotInstallPath) {
            this.snapshotInstallPath = snapshotInstallPath;
        }
    }

    public static class ReinitImageCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        private String imagePath;
        @GrayVersion(value = "5.0.0")
        private String volumePath;

        public String getImagePath() {
            return imagePath;
        }

        public void setImagePath(String imagePath) {
            this.imagePath = imagePath;
        }

        public String getVolumePath() {
            return volumePath;
        }

        public void setVolumePath(String volumePath) {
            this.volumePath = volumePath;
        }
    }

    public static class ReinitImageRsp extends AgentResponse {
        @Validation
        private String newVolumeInstallPath;

        public String getNewVolumeInstallPath() {
            return newVolumeInstallPath;
        }

        public void setNewVolumeInstallPath(String newVolumeInstallPath) {
            this.newVolumeInstallPath = newVolumeInstallPath;
        }
    }

    public static class RevertVolumeFromSnapshotRsp extends AgentResponse {
        @Validation
        private String newVolumeInstallPath;

        @Validation
        private long size;

        public String getNewVolumeInstallPath() {
            return newVolumeInstallPath;
        }

        public void setNewVolumeInstallPath(String newVolumeInstallPath) {
            this.newVolumeInstallPath = newVolumeInstallPath;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }
    }

    public static class MergeSnapshotCmd extends AgentCommand implements HasThreadContext {
        @GrayVersion(value = "5.0.0")
        private String volumeUuid;
        @GrayVersion(value = "5.0.0")
        private String snapshotInstallPath;
        @GrayVersion(value = "5.0.0")
        private String workspaceInstallPath;

        public String getVolumeUuid() {
            return volumeUuid;
        }

        public void setVolumeUuid(String volumeUuid) {
            this.volumeUuid = volumeUuid;
        }

        public String getSnapshotInstallPath() {
            return snapshotInstallPath;
        }

        public void setSnapshotInstallPath(String snapshotInstallPath) {
            this.snapshotInstallPath = snapshotInstallPath;
        }

        public String getWorkspaceInstallPath() {
            return workspaceInstallPath;
        }

        public void setWorkspaceInstallPath(String workspaceInstallPath) {
            this.workspaceInstallPath = workspaceInstallPath;
        }
    }

    public static class MergeSnapshotRsp extends AgentResponse {
        private long size;
        private long actualSize;

        public long getActualSize() {
            return actualSize;
        }

        public void setActualSize(long actualSize) {
            this.actualSize = actualSize;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }
    }

    public static class RebaseAndMergeSnapshotsCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        private String volumeUuid;
        @GrayVersion(value = "5.0.0")
        private List<String> snapshotInstallPaths;
        @GrayVersion(value = "5.0.0")
        private String workspaceInstallPath;

        public String getVolumeUuid() {
            return volumeUuid;
        }

        public void setVolumeUuid(String volumeUuid) {
            this.volumeUuid = volumeUuid;
        }

        public List<String> getSnapshotInstallPaths() {
            return snapshotInstallPaths;
        }

        public void setSnapshotInstallPaths(List<String> snapshotInstallPaths) {
            this.snapshotInstallPaths = snapshotInstallPaths;
        }

        public String getWorkspaceInstallPath() {
            return workspaceInstallPath;
        }

        public void setWorkspaceInstallPath(String workspaceInstallPath) {
            this.workspaceInstallPath = workspaceInstallPath;
        }
    }

    public static class RebaseAndMergeSnapshotsRsp extends AgentResponse {
        private long size;
        private long actualSize;

        public long getActualSize() {
            return actualSize;
        }

        public void setActualSize(long actualSize) {
            this.actualSize = actualSize;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }
    }

    public static class OfflineMergeSnapshotCmd extends AgentCommand implements HasThreadContext {
        @GrayVersion(value = "5.0.0")
        private String srcPath;
        @GrayVersion(value = "5.0.0")
        private String destPath;
        @GrayVersion(value = "5.0.0")
        private boolean fullRebase;

        public boolean isFullRebase() {
            return fullRebase;
        }

        public void setFullRebase(boolean fullRebase) {
            this.fullRebase = fullRebase;
        }

        public String getSrcPath() {
            return srcPath;
        }

        public void setSrcPath(String srcPath) {
            this.srcPath = srcPath;
        }

        public String getDestPath() {
            return destPath;
        }

        public void setDestPath(String destPath) {
            this.destPath = destPath;
        }
    }

    public static class OfflineMergeSnapshotRsp extends AgentResponse {
    }

    public static class CheckBitsCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        public String path;
        @GrayVersion(value = "5.0.0")
        public String username;
    }

    public static class CheckBitsRsp extends AgentResponse {
        public boolean existing;
    }

    public static class GetMd5TO {
        public String resourceUuid;
        public String path;
    }

    public static class GetMd5Cmd extends AgentCommand implements HasThreadContext {
        @GrayVersion(value = "5.0.0")
        public List<GetMd5TO> md5s;
        @GrayVersion(value = "5.0.0")
        public String sendCommandUrl;
        @GrayVersion(value = "5.0.0")
        public String volumeUuid;
        @GrayVersion(value = "5.0.0")
        public String stage;
    }

    public static class Md5TO {
        public String resourceUuid;
        public String path;
        public String md5;
    }

    public static class GetMd5Rsp extends AgentResponse {
        public List<Md5TO> md5s;
    }


    public static class CheckMd5sumCmd extends AgentCommand implements HasThreadContext {
        @GrayVersion(value = "5.0.0")
        public List<Md5TO> md5s;
        @GrayVersion(value = "5.0.0")
        public String sendCommandUrl;
        @GrayVersion(value = "5.0.0")
        public String volumeUuid;
        @GrayVersion(value = "5.0.0")
        public String stage;
    }

    public static class GetBackingFileCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        public String path;
        @GrayVersion(value = "5.0.0")
        public String volumeUuid;
    }

    public static class GetBackingFileRsp extends AgentResponse {
        public String backingFilePath;
        public Long size;
    }

    /**
     * volumeInstallDir contains all snapshots and base volume file,
     * their backing file in imageCacheDir will be identified as the base image.
     * This takes into consideration multiple snapshot chains and chain-based image.
     */
    public static class GetVolumeBaseImagePathCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        public String volumeUuid;
        @GrayVersion(value = "5.0.0")
        public String imageCacheDir;
        @GrayVersion(value = "5.0.0")
        public String volumeInstallDir;
        @GrayVersion(value = "5.0.0")
        public String volumeInstallPath;
    }

    public static class GetVolumeBaseImagePathRsp extends AgentResponse {
        public String path;
        public List<String> otherPaths;
        public Long size;
    }

    public static class GetBackingChainCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        public String volumeUuid;
        @GrayVersion(value = "5.0.0")
        public String installPath;
    }

    public static class GetBackingChainRsp extends AgentResponse {
        public List<String> backingChain;
        public long totalSize;
    }

    public static class GetVolumeSizeCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        public String volumeUuid;
        @GrayVersion(value = "5.0.0")
        public String installPath;
    }

    public static class GetVolumeSizeRsp extends AgentResponse {
        public long actualSize;
        public long size;
    }

    public static class GetBatchVolumeSizeCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        public Map<String, String> volumeUuidInstallPaths;
    }

    public static class GetBatchVolumeSizeRsp extends AgentResponse {
        public Map<String, Long> actualSizes;
    }

    public static class GetQCOW2ReferenceCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        public String path;
        @GrayVersion(value = "5.0.0")
        public String searchingDir;
    }

    public static class GetQCOW2ReferenceRsp extends AgentResponse {
        List<String> referencePaths;
    }

    public static class CheckInitializedFileCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        public String filePath;
    }

    public static class CheckInitializedFileRsp extends AgentResponse {
        public boolean existed = true;
    }

    public static class CreateInitializedFileCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        public String filePath;
    }

    public static class Qcow2Cmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        public String preallocation = buildQcow2Options();
    }

    public static class DownloadBitsFromKVMHostCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        public String hostname;
        @GrayVersion(value = "5.0.0")
        public String username;
        @GrayVersion(value = "5.0.0")
        public String sshKey;
        @GrayVersion(value = "5.0.0")
        public int sshPort;
        // it's file path on kvm host actually
        @GrayVersion(value = "5.0.0")
        public String backupStorageInstallPath;
        @GrayVersion(value = "5.0.0")
        public String primaryStorageInstallPath;
        @GrayVersion(value = "5.0.0")
        public Long bandWidth;
        @GrayVersion(value = "5.0.0")
        public String identificationCode;
    }

    public static class DownloadBitsFromKVMHostRsp extends AgentResponse {
        public String format;
    }

    public static class CancelDownloadBitsFromKVMHostCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        public String primaryStorageInstallPath;
    }

    public static class GetDownloadBitsFromKVMHostProgressCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        public List<String> volumePaths;
    }

    public static class GetDownloadBitsFromKVMHostProgressRsp extends AgentResponse {
        public long totalSize;
    }

    public static class LinkVolumeNewDirCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        public String volumeUuid;
        @GrayVersion(value = "5.0.0")
        public String srcDir;
        @GrayVersion(value = "5.0.0")
        public String dstDir;
    }

    public static class LinkVolumeNewDirRsp extends AgentResponse {
    }

    public static class GetQcow2HashValueCmd extends AgentCommand {
        @GrayVersion(value = "5.0.0")
        private String hostUuid;
        @GrayVersion(value = "5.0.0")
        private String installPath;

        public String getHostUuid() {
            return hostUuid;
        }

        public void setHostUuid(String hostUuid) {
            this.hostUuid = hostUuid;
        }

        public String getInstallPath() {
            return installPath;
        }

        public void setInstallPath(String installPath) {
            this.installPath = installPath;
        }
    }

    public static class GetQcow2HashValueRsp extends AgentResponse {
        private String hashValue;

        public String getHashValue() {
            return hashValue;
        }

        public void setHashValue(String hashValue) {
            this.hashValue = hashValue;
        }
    }

    public static final String INIT_PATH = "/localstorage/init";
    public static final String GET_PHYSICAL_CAPACITY_PATH = "/localstorage/getphysicalcapacity";
    public static final String CREATE_EMPTY_VOLUME_PATH = "/localstorage/volume/createempty";
    public static final String CREATE_FOLDER_PATH = "/localstorage/volume/createfolder";
    public static final String CREATE_VOLUME_FROM_CACHE_PATH = "/localstorage/volume/createvolumefromcache";
    public static final String CREATE_VOLUME_WITH_BACKING_PATH = "/localstorage/volume/createwithbacking";
    public static final String DELETE_BITS_PATH = "/localstorage/delete";
    public static final String DELETE_DIR_PATH = "/localstorage/deletedir";
    public static final String CHECK_BITS_PATH = "/localstorage/checkbits";
    public static final String UNLINK_BITS_PATH = "/localstorage/unlink";
    public static final String CREATE_TEMPLATE_FROM_VOLUME = "/localstorage/volume/createtemplate";
    public static final String ESTIMATE_TEMPLATE_SIZE_PATH = "/localstorage/volume/estimatetemplatesize";
    public static final String REVERT_SNAPSHOT_PATH = "/localstorage/snapshot/revert";
    public static final String REINIT_IMAGE_PATH = "/localstorage/reinit/image";
    public static final String MERGE_SNAPSHOT_PATH = "/localstorage/snapshot/merge";
    public static final String OFFLINE_MERGE_PATH = "/localstorage/snapshot/offlinemerge";
    public static final String GET_MD5_PATH = "/localstorage/getmd5";
    public static final String CHECK_MD5_PATH = "/localstorage/checkmd5";
    public static final String GET_BACKING_FILE_PATH = "/localstorage/volume/getbackingfile";
    public static final String GET_VOLUME_SIZE = "/localstorage/volume/getsize";
    public static final String BATCH_GET_VOLUME_SIZE = "/localstorage/volume/batchgetsize";
    public static final String HARD_LINK_VOLUME = "/localstorage/volume/hardlink";
    public static final String GET_BASE_IMAGE_PATH = "/localstorage/volume/getbaseimagepath";
    public static final String GET_BACKING_CHAIN_PATH = "/localstorage/volume/getbackingchain";
    public static final String GET_QCOW2_REFERENCE = "/localstorage/getqcow2reference";
    public static final String CHECK_INITIALIZED_FILE = "/localstorage/check/initializedfile";
    public static final String CREATE_INITIALIZED_FILE = "/localstorage/create/initializedfile";
    public static final String DOWNLOAD_BITS_FROM_KVM_HOST_PATH = "/localstorage/kvmhost/download";
    public static final String CANCEL_DOWNLOAD_BITS_FROM_KVM_HOST_PATH = "/localstorage/kvmhost/download/cancel";
    public static final String GET_DOWNLOAD_BITS_FROM_KVM_HOST_PROGRESS_PATH = "/localstorage/kvmhost/download/progress";
    public static final String GET_QCOW2_HASH_VALUE_PATH = "/localstorage/getqcow2hash";

    public LocalStorageKvmBackend() {
    }

    public LocalStorageKvmBackend(PrimaryStorageVO self) {
        super(self);
    }

    public String makeTemporaryRootVolumeInstallUrl(VolumeInventory vol, String originVolumeUuid) {
        return PathUtil.join(self.getUrl(), PrimaryStoragePathMaker.makeTemporaryRootVolumeInstallPath(vol, originVolumeUuid));
    }

    public String makeTemporaryDataVolumeInstallUrl(String volUuid, String originVolumeUuid) {
        return PathUtil.join(self.getUrl(), PrimaryStoragePathMaker.makeTemporaryDataVolumeInstallPath(volUuid, originVolumeUuid));
    }

    public String makeRootVolumeInstallUrl(VolumeInventory vol) {
        return PathUtil.join(self.getUrl(), PrimaryStoragePathMaker.makeRootVolumeInstallPath(vol));
    }

    public String makeMemoryVolumeInstallUrl(VolumeInventory vol) {
        return PathUtil.join(self.getUrl(), PrimaryStoragePathMaker.makeMemoryVolumeInstallPath(vol));
    }

    public String makeDataVolumeInstallUrl(String volUuid) {
        return PathUtil.join(self.getUrl(), PrimaryStoragePathMaker.makeDataVolumeInstallPath(volUuid));
    }

    public boolean isCachedImageUrl(String path){
        return path.startsWith(PathUtil.join(self.getUrl(), PrimaryStoragePathMaker.getCachedImageInstallDir()));
    }

    public String getCachedImageDir(){
        return PathUtil.join(self.getUrl(), PrimaryStoragePathMaker.getCachedImageInstallDir());
    }

    public String makeCachedImageInstallUrl(ImageInventory iminv) {
        return ImageCacheUtil.getImageCachePath(iminv, it -> PathUtil.join(self.getUrl(), PrimaryStoragePathMaker.makeCachedImageInstallPath(iminv)));
    }

    public String makeCachedImageInstallUrlFromImageUuidForTemplate(String imageUuid) {
        return PathUtil.join(self.getUrl(), PrimaryStoragePathMaker.makeCachedImageInstallPathFromImageUuidForTemplate(imageUuid));
    }

    public String makeTemplateFromVolumeInWorkspacePath(String imageUuid) {
        return PathUtil.join(self.getUrl(), "templateWorkspace", String.format("image-%s", imageUuid), String.format("%s.qcow2", imageUuid));
    }

    public String makeVolumeInstallDir(VolumeInventory vol) {
        String volPath = null;
        if (VolumeType.Data.toString().equals(vol.getType())) {
            volPath = makeDataVolumeInstallUrl(vol.getUuid());
        } else if (VolumeType.Root.toString().equals(vol.getType())) {
            volPath = makeRootVolumeInstallUrl(vol);
        } else if (VolumeType.Memory.toString().equals(vol.getType())) {
            volPath = makeMemoryVolumeInstallUrl(vol);
        } else if (VolumeType.Cache.toString().equals(vol.getType())) {
            volPath = makeDataVolumeInstallUrl(vol.getUuid());
        }

        DebugUtils.Assert(!StringUtils.isEmpty(volPath), "volPath should not be null");
        return new File(volPath).getParentFile().getAbsolutePath();
    }

    public String makeSnapshotInstallPath(VolumeInventory vol, VolumeSnapshotInventory snapshot) {
        String volDir = makeVolumeInstallDir(vol);
        return PathUtil.join(volDir, "snapshots", String.format("%s.qcow2", snapshot.getUuid()));
    }

    public String makeSnapshotInstallPath(VolumeInventory vol, String snapshotUuid) {
        String volDir = makeVolumeInstallDir(vol);
        return PathUtil.join(volDir, "snapshots", String.format("%s.qcow2", snapshotUuid));
    }

    public String makeSnapshotWorkspacePath(String imageUuid) {
        return PathUtil.join(
                self.getUrl(),
                PrimaryStoragePathMaker.makeImageFromSnapshotWorkspacePath(imageUuid),
                String.format("%s.qcow2", imageUuid)
        );
    }

    public static String buildQcow2Options() {
        StringBuilder options = new StringBuilder();

        if (LocalStorageConstants.VALID_QCOW2_ALLOCATION.contains(LocalStoragePrimaryStorageGlobalConfig.QCOW2_ALLOCATION.value()) &&
                !LocalStoragePrimaryStorageGlobalConfig.QCOW2_ALLOCATION.value().equals("none")) {
            options.append(String.format(" -o preallocation=%s ", LocalStoragePrimaryStorageGlobalConfig.QCOW2_ALLOCATION.value()));
        }
        return options.toString();
    }

    @Override
    void syncPhysicalCapacityInCluster(List<ClusterInventory> clusters, final ReturnValueCompletion<PhysicalCapacityUsage> completion) {
        List<String> clusterUuids = CollectionUtils.transformToList(clusters, ClusterInventory::getUuid);

        final PhysicalCapacityUsage ret = new PhysicalCapacityUsage();

        SimpleQuery<HostVO> q = dbf.createQuery(HostVO.class);
        q.select(HostVO_.uuid);
        q.add(HostVO_.clusterUuid, Op.IN, clusterUuids);
        final List<String> hostInClusterUuids = q.listValue();

        if (hostInClusterUuids.isEmpty()) {
            completion.success(ret);
            return;
        }

        SimpleQuery<LocalStorageHostRefVO> sq = dbf.createQuery(LocalStorageHostRefVO.class);
        sq.select(LocalStorageHostRefVO_.hostUuid);
        sq.add(LocalStorageHostRefVO_.primaryStorageUuid, Op.EQ, self.getUuid());
        sq.add(LocalStorageHostRefVO_.hostUuid, Op.IN, hostInClusterUuids);
        final List<String> hostUuids = sq.listValue();

        if (hostUuids.isEmpty()) {
            completion.success(ret);
            return;
        }

        List<KVMHostAsyncHttpCallMsg> msgs = CollectionUtils.transformToList(hostUuids, new Function<KVMHostAsyncHttpCallMsg, String>() {
            @Override
            public KVMHostAsyncHttpCallMsg call(String arg) {
                GetPhysicalCapacityCmd cmd = new GetPhysicalCapacityCmd();
                cmd.uuid = self.getUuid();
                cmd.setHostUuid(arg);
                cmd.storagePath = self.getUrl();

                KVMHostAsyncHttpCallMsg msg = new KVMHostAsyncHttpCallMsg();
                msg.setHostUuid(arg);
                msg.setCommand(cmd);
                msg.setPath(GET_PHYSICAL_CAPACITY_PATH);
                bus.makeTargetServiceIdByResourceUuid(msg, HostConstant.SERVICE_ID, arg);
                return msg;
            }
        });

        bus.send(msgs, new CloudBusListCallBack(completion) {
            @Override
            public void run(List<MessageReply> replies) {
                for (MessageReply reply : replies) {
                    String hostUuid = hostUuids.get(replies.indexOf(reply));

                    if (!reply.isSuccess()) {
                        logger.warn(String.format("cannot get the physical capacity of local storage on the host[uuid:%s], %s", hostUuid, reply.getError()));
                        continue;
                    }

                    KVMHostAsyncHttpCallReply r = reply.castReply();
                    AgentResponse rsp = r.toResponse(AgentResponse.class);

                    if (!rsp.isSuccess()) {
                        logger.warn(String.format("cannot get the physical capacity of local storage on the host[uuid:%s], %s", hostUuid, rsp.getError()));
                        continue;
                    }

                    ret.totalPhysicalSize += rsp.getTotalCapacity();
                    ret.availablePhysicalSize += rsp.getAvailableCapacity();
                }

                completion.success(ret);
            }
        });
    }

    protected <T extends AgentResponse> void httpCall(String path, final String hostUuid, AgentCommand cmd, final Class<T> rspType, final ReturnValueCompletion<T> completion) {
        httpCall(path, hostUuid, cmd, false, rspType, completion);
    }

    protected <T extends AgentResponse> void httpCall(String path, final String hostUuid, AgentCommand cmd, boolean noCheckStatus, final Class<T> rspType, final ReturnValueCompletion<T> completion) {
        cmd.uuid = self.getUuid();
        cmd.storagePath = self.getUrl();
        cmd.primaryStorageUuid = cmd.uuid;

        KVMHostAsyncHttpCallMsg msg = new KVMHostAsyncHttpCallMsg();
        msg.setHostUuid(hostUuid);
        msg.setPath(path);
        msg.setNoStatusCheck(noCheckStatus);
        msg.setCommand(cmd);
        bus.makeTargetServiceIdByResourceUuid(msg, HostConstant.SERVICE_ID, hostUuid);
        bus.send(msg, new CloudBusCallBack(completion) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    completion.fail(reply.getError());
                    return;
                }

                KVMHostAsyncHttpCallReply r = reply.castReply();
                T rsp = r.toResponse(rspType);
                ErrorCode errorCode = rsp.buildErrorCode();
                if (errorCode != null) {
                    completion.fail(errorCode);
                    return;
                }

                if (rsp.getTotalCapacity() != null && rsp.getAvailableCapacity() != null) {
                    new LocalStorageCapacityUpdater().updatePhysicalCapacityByKvmAgentResponse(self.getUuid(), hostUuid, rsp);
                }

                completion.success(rsp);
            }
        });
    }

    @Override
    protected void handle(final InstantiateVolumeOnPrimaryStorageMsg msg, final ReturnValueCompletion<InstantiateVolumeOnPrimaryStorageReply> completion) {
        if (msg instanceof InstantiateTemporaryRootVolumeFromTemplateOnPrimaryStorageMsg) {
            createTemporaryRootVolume((InstantiateTemporaryRootVolumeFromTemplateOnPrimaryStorageMsg) msg, completion);
        } else if (msg instanceof InstantiateRootVolumeFromTemplateOnPrimaryStorageMsg) {
            createRootVolume((InstantiateRootVolumeFromTemplateOnPrimaryStorageMsg) msg, completion);
        } else if (msg instanceof InstantiateTemporaryVolumeOnPrimaryStorageMsg) {
            createTemporaryEmptyVolume((InstantiateTemporaryVolumeOnPrimaryStorageMsg) msg, completion);
        } else if (msg instanceof InstantiateMemoryVolumeOnPrimaryStorageMsg) {
            createMemoryVolume((InstantiateMemoryVolumeOnPrimaryStorageMsg) msg, completion);
        } else {
            createEmptyVolume(msg, completion);
        }
    }

    private void createMemoryVolume(final InstantiateMemoryVolumeOnPrimaryStorageMsg msg, final ReturnValueCompletion<InstantiateVolumeOnPrimaryStorageReply> completion) {
        final CreateFolderCmd cmd = new CreateFolderCmd();
        VolumeInventory volume = msg.getVolume();
        cmd.setInstallUrl(makeVolumeInstallDir(volume));
        httpCall(CREATE_FOLDER_PATH, msg.getDestHost().getUuid(), cmd, AgentResponse.class, new ReturnValueCompletion<AgentResponse>(completion) {
            @Override
            public void success(AgentResponse rsp) {
                InstantiateVolumeOnPrimaryStorageReply r = new InstantiateVolumeOnPrimaryStorageReply();
                VolumeInventory vol = msg.getVolume();
                vol.setInstallPath(cmd.installUrl);
                vol.setFormat(VolumeConstant.VOLUME_FORMAT_QCOW2);
                r.setVolume(vol);
                completion.success(r);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(operr("unable to create an empty volume[uuid:%s, name:%s] on the kvm host[uuid:%s]",
                        volume.getUuid(), volume.getName(), msg.getDestHost().getUuid()).causedBy(errorCode));
            }
        });
    }

    @Override
    void handle(DownloadVolumeTemplateToPrimaryStorageMsg msg, ReturnValueCompletion<DownloadVolumeTemplateToPrimaryStorageReply> completion) {
        DownloadVolumeTemplateToPrimaryStorageReply reply = new DownloadVolumeTemplateToPrimaryStorageReply();
        ImageSpec ispec = msg.getTemplateSpec();

        BackupStorageInventory bsinv = null;
        String backupStorageInstallPath = null;

        if (ispec.getSelectedBackupStorage() != null) {
            SimpleQuery<BackupStorageVO> q = dbf.createQuery(BackupStorageVO.class);
            q.add(BackupStorageVO_.uuid, Op.EQ, ispec.getSelectedBackupStorage().getBackupStorageUuid());
            BackupStorageVO bs = q.find();

            bsinv = BackupStorageInventory.valueOf(bs);
            backupStorageInstallPath = ispec.getSelectedBackupStorage().getInstallPath();
        } else if (ispec.isNeedDownload()) {
            ImageBackupStorageSelector selector = new ImageBackupStorageSelector();
            selector.setZoneUuid(self.getZoneUuid());
            selector.setImageUuid(ispec.getInventory().getUuid());
            final String bsUuid = selector.select();
            if (bsUuid == null) {
                throw new OperationFailureException(operr(
                        "the image[uuid:%s, name: %s] is not available to download on any backup storage:\n" +
                                "1. check if image is in status of Deleted\n" +
                                "2. check if the backup storage on which the image is shown as Ready is attached to the zone[uuid:%s]",
                        ispec.getInventory().getUuid(), ispec.getInventory().getName(), self.getZoneUuid()));
            }

            bsinv = BackupStorageInventory.valueOf(dbf.findByUuid(bsUuid, BackupStorageVO.class));
            ImageBackupStorageRefInventory ref = CollectionUtils.find(ispec.getInventory().getBackupStorageRefs(), new Function<ImageBackupStorageRefInventory, ImageBackupStorageRefInventory>() {
                @Override
                public ImageBackupStorageRefInventory call(ImageBackupStorageRefInventory arg) {
                    return arg.getBackupStorageUuid().equals(bsUuid) ? arg : null;
                }
            });

            backupStorageInstallPath = ref.getInstallPath();
        }

        String pathInCache = makeCachedImageInstallUrl(ispec.getInventory());

        ImageCache cache = new ImageCache();
        cache.backupStorage = bsinv;
        cache.backupStorageInstallPath = backupStorageInstallPath;
        cache.primaryStorageInstallPath = pathInCache;
        cache.hostUuid = msg.getHostUuid();
        cache.image = ispec.getInventory();
        cache.download(new ReturnValueCompletion<ImageCacheInventory>(completion) {
            @Override
            public void success(ImageCacheInventory returnValue) {
                reply.setImageCache(returnValue);
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }


    private void createTemporaryEmptyVolume(InstantiateTemporaryVolumeOnPrimaryStorageMsg msg, ReturnValueCompletion<InstantiateVolumeOnPrimaryStorageReply> completion) {
        final VolumeInventory volume = msg.getVolume();
        if (VolumeType.Root.toString().equals(volume.getType())) {
            volume.setInstallPath(makeTemporaryRootVolumeInstallUrl(volume, msg.getOriginVolumeUuid()));
        } else {
            volume.setInstallPath(makeTemporaryDataVolumeInstallUrl(volume.getUuid(), msg.getOriginVolumeUuid()));
        }
        createEmptyVolume(msg, completion);
    }

    private void createEmptyVolume(InstantiateVolumeOnPrimaryStorageMsg msg, ReturnValueCompletion<InstantiateVolumeOnPrimaryStorageReply> completion) {
        createEmptyVolume(msg.getVolume(), msg.getDestHost().getUuid(), new ReturnValueCompletion<VolumeStats>(completion) {
            @Override
            public void success(VolumeStats returnValue) {
                InstantiateVolumeOnPrimaryStorageReply r = new InstantiateVolumeOnPrimaryStorageReply();
                VolumeInventory vol = msg.getVolume();
                vol.setInstallPath(returnValue.getInstallPath());
                vol.setActualSize(returnValue.getActualSize());
                vol.setFormat(VolumeConstant.VOLUME_FORMAT_QCOW2);
                if (returnValue.getSize() != null) {
                    vol.setSize(returnValue.getSize());
                }

                r.setVolume(vol);
                completion.success(r);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    public void createEmptyVolume(final VolumeInventory volume, final String hostUuid, final ReturnValueCompletion<VolumeStats> completion) {
        createEmptyVolumeWithBackingFile(volume, hostUuid, null, completion);
    }

    public void createEmptyVolumeWithBackingFile(final VolumeInventory volume, final String hostUuid, final String backingFile, final ReturnValueCompletion<VolumeStats> completion) {
        final CreateEmptyVolumeCmd cmd = new CreateEmptyVolumeCmd();
        cmd.setAccountUuid(acntMgr.getOwnerAccountUuidOfResource(volume.getUuid()));
        if (volume.getInstallPath() != null && !volume.getInstallPath().equals("")) {
            cmd.setInstallUrl(volume.getInstallPath());
        } else {
            if (VolumeType.Root.toString().equals(volume.getType())) {
                cmd.setInstallUrl(makeRootVolumeInstallUrl(volume));
            } else if (VolumeType.Data.toString().equals(volume.getType())) {
                cmd.setInstallUrl(makeDataVolumeInstallUrl(volume.getUuid()));
            } else if (VolumeType.Memory.toString().equals(volume.getType())) {
                cmd.setInstallUrl(makeMemoryVolumeInstallUrl(volume));
            } else if (VolumeType.Cache.toString().equals(volume.getType())) {
                cmd.setInstallUrl(makeDataVolumeInstallUrl(volume.getUuid()));
            }
        }
        cmd.setName(volume.getName());
        cmd.setSize(volume.getSize());
        cmd.setVolumeUuid(volume.getUuid());
        cmd.setBackingFile(backingFile);

        httpCall(CREATE_EMPTY_VOLUME_PATH, hostUuid, cmd, CreateEmptyVolumeRsp.class, new ReturnValueCompletion<CreateEmptyVolumeRsp>(completion) {
            @Override
            public void success(CreateEmptyVolumeRsp returnValue) {
                completion.success(new VolumeStats(cmd.getInstallUrl(), returnValue.actualSize, returnValue.size));
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(operr("unable to create an empty volume[uuid:%s, name:%s] on the kvm host[uuid:%s]",
                        volume.getUuid(), volume.getName(), hostUuid).causedBy(errorCode));
            }
        });
    }
    private String getHostUuidByResourceUuid(String resUuid, String resType) {
        SimpleQuery<LocalStorageResourceRefVO> q = dbf.createQuery(LocalStorageResourceRefVO.class);
        q.select(LocalStorageResourceRefVO_.hostUuid);
        q.add(LocalStorageResourceRefVO_.primaryStorageUuid, Op.EQ, self.getUuid());
        q.add(LocalStorageResourceRefVO_.resourceUuid, Op.EQ, resUuid);
        String hostUuid = q.findValue();

        if (hostUuid == null) {
            throw new CloudRuntimeException(String.format("resource[uuid:%s, type:%s] is not any on any host of local primary storage[uuid:%s]",
                    resUuid, resType, self.getUuid()));
        }

        return hostUuid;
    }

    class ImageCache {
        ImageInventory image;
        BackupStorageInventory backupStorage;
        String volumeResourceInstallPath;
        boolean incremental;
        String hostUuid;
        String primaryStorageInstallPath;
        String backupStorageInstallPath;

        void download(final ReturnValueCompletion<ImageCacheInventory> completion) {
            DebugUtils.Assert(image != null, "image cannot be null");
            DebugUtils.Assert(hostUuid != null, "host uuid cannot be null");
            DebugUtils.Assert(primaryStorageInstallPath != null, "primaryStorageInstallPath cannot be null");

            thdf.chainSubmit(new ChainTask(completion) {
                @Override
                public String getSyncSignature() {
                    return String.format("download-image-%s-to-localstorage-%s-cache-host-%s", image.getUuid(), self.getUuid(), hostUuid);
                }

                private void doDownload(final SyncTaskChain chain) {
                    if (volumeResourceInstallPath == null) {
                        DebugUtils.Assert(backupStorage != null, "backup storage cannot be null");
                        DebugUtils.Assert(backupStorageInstallPath != null, "backupStorageInstallPath cannot be null");
                    }

                    taskProgress("Download the image[%s] to the image cache", image.getName());

                    FlowChain fchain = FlowChainBuilder.newShareFlowChain();
                    fchain.setName(String.format("download-image-%s-to-local-storage-%s-cache-host-%s",
                            image.getUuid(), self.getUuid(), hostUuid));
                    fchain.then(new ShareFlow() {
                        String psUuid;
                        long actualSize = image.getActualSize();
                        String allocatedInstallUrl;

                        @Override
                        public void setup() {
                            flow(new Flow() {
                                String __name__ = "allocate-primary-storage";

                                boolean s = false;

                                @Override
                                public void run(final FlowTrigger trigger, Map data) {
                                    AllocatePrimaryStorageSpaceMsg amsg = new AllocatePrimaryStorageSpaceMsg();
                                    amsg.setRequiredPrimaryStorageUuid(self.getUuid());
                                    amsg.setRequiredHostUuid(hostUuid);
                                    amsg.setSize(image.getActualSize());
                                    amsg.setPurpose(PrimaryStorageAllocationPurpose.DownloadImage.toString());
                                    amsg.setNoOverProvisioning(true);
                                    amsg.setImageUuid(image.getUuid());
                                    bus.makeLocalServiceId(amsg, PrimaryStorageConstant.SERVICE_ID);
                                    bus.send(amsg, new CloudBusCallBack(trigger) {
                                        @Override
                                        public void run(MessageReply reply) {
                                            if (!reply.isSuccess()) {
                                                trigger.fail(reply.getError());
                                                return;
                                            }
                                            s = true;
                                            AllocatePrimaryStorageSpaceReply ar = (AllocatePrimaryStorageSpaceReply) reply;
                                            allocatedInstallUrl = ar.getAllocatedInstallUrl();
                                            psUuid = ar.getPrimaryStorageInventory().getUuid();
                                            trigger.next();
                                        }
                                    });
                                }

                                @Override
                                public void rollback(FlowRollback trigger, Map data) {
                                    if (s) {
                                        ReleasePrimaryStorageSpaceMsg rmsg = new ReleasePrimaryStorageSpaceMsg();
                                        rmsg.setAllocatedInstallUrl(allocatedInstallUrl);
                                        rmsg.setDiskSize(image.getActualSize());
                                        rmsg.setNoOverProvisioning(true);
                                        rmsg.setPrimaryStorageUuid(self.getUuid());
                                        bus.makeLocalServiceId(rmsg, PrimaryStorageConstant.SERVICE_ID);
                                        bus.send(rmsg);
                                    }

                                    trigger.rollback();
                                }
                            });
                            flow(new NoRollbackFlow() {
                                String __name__ = "download";

                                @Override
                                public void run(final FlowTrigger trigger, Map data) {
                                    if (volumeResourceInstallPath != null) {
                                        if (incremental) {
                                            incrementalDownloadFromVolume(trigger);
                                        } else {
                                            downloadFromVolume(trigger);
                                        }
                                    } else {
                                        downloadFromBackupStorage(trigger);
                                    }
                                }

                                private void incrementalDownloadFromVolume(FlowTrigger trigger) {
                                    CreateVolumeWithBackingCmd cmd = new CreateVolumeWithBackingCmd();
                                    cmd.installPath = primaryStorageInstallPath;
                                    cmd.templatePathInCache = volumeResourceInstallPath;

                                    httpCall(CREATE_VOLUME_WITH_BACKING_PATH, hostUuid, cmd, false,
                                            CreateVolumeWithBackingRsp.class,
                                            new ReturnValueCompletion<CreateVolumeWithBackingRsp>(trigger) {
                                                @Override
                                                public void success(CreateVolumeWithBackingRsp rsp) {
                                                    actualSize = rsp.actualSize;
                                                    trigger.next();
                                                }

                                                @Override
                                                public void fail(ErrorCode errorCode) {
                                                    trigger.fail(errorCode);
                                                }
                                            });
                                }

                                private void downloadFromVolume(FlowTrigger trigger) {
                                    CreateTemplateFromVolumeCmd cmd = new CreateTemplateFromVolumeCmd();
                                    cmd.setInstallPath(primaryStorageInstallPath);
                                    cmd.setVolumePath(volumeResourceInstallPath);

                                    httpCall(CREATE_TEMPLATE_FROM_VOLUME, hostUuid, cmd, false,
                                            CreateTemplateFromVolumeRsp.class,
                                            new ReturnValueCompletion<CreateTemplateFromVolumeRsp>(trigger) {
                                                @Override
                                                public void success(CreateTemplateFromVolumeRsp rsp) {
                                                    actualSize = rsp.actualSize;
                                                    trigger.next();
                                                }

                                                @Override
                                                public void fail(ErrorCode errorCode) {
                                                    trigger.fail(errorCode);
                                                }
                                            });
                                }

                                private void downloadFromBackupStorage(FlowTrigger trigger) {
                                    LocalStorageBackupStorageMediator m = localStorageFactory.getBackupStorageMediator(KVMConstant.KVM_HYPERVISOR_TYPE, backupStorage.getType());
                                    m.downloadBits(getSelfInventory(), backupStorage,
                                            backupStorageInstallPath, primaryStorageInstallPath,
                                            hostUuid, false, new Completion(trigger) {
                                                @Override
                                                public void success() {
                                                    trigger.next();
                                                }

                                                @Override
                                                public void fail(ErrorCode errorCode) {
                                                    trigger.fail(errorCode);
                                                }
                                            });
                                }
                            });

                            done(new FlowDoneHandler(completion, chain) {
                                @Override
                                public void handle(Map data) {
                                    ImageCacheVO vo = new ImageCacheVO();
                                    vo.setState(ImageCacheState.ready);
                                    vo.setMediaType(ImageMediaType.valueOf(image.getMediaType()));
                                    vo.setImageUuid(image.getUuid());
                                    vo.setPrimaryStorageUuid(self.getUuid());
                                    vo.setSize(actualSize);
                                    vo.setMd5sum("not calculated");

                                    LocalStorageUtils.InstallPath path = new LocalStorageUtils.InstallPath();
                                    path.installPath = primaryStorageInstallPath;
                                    path.hostUuid = hostUuid;
                                    vo.setInstallUrl(path.makeFullPath());
                                    dbf.persist(vo);

                                    logger.debug(String.format("downloaded image[uuid:%s, name:%s] to the image cache of local primary storage[uuid: %s, installPath: %s] on host[uuid: %s]",
                                            image.getUuid(), image.getName(), self.getUuid(), primaryStorageInstallPath, hostUuid));

                                    ImageCacheInventory inv = ImageCacheInventory.valueOf(vo);
                                    inv.setInstallUrl(primaryStorageInstallPath);

                                    new While<>(pluginRgty.getExtensionList(AfterCreateImageCacheExtensionPoint.class)).each((ext, whileCompletion) -> {
                                        ext.saveEncryptAfterCreateImageCache(hostUuid, inv, new Completion(whileCompletion) {
                                            @Override
                                            public void success() {
                                                whileCompletion.done();
                                            }

                                            @Override
                                            public void fail(ErrorCode errorCode) {
                                                whileCompletion.addError(errorCode);
                                                whileCompletion.allDone();
                                            }
                                        });
                                    }).run(new WhileDoneCompletion(completion) {
                                        @Override
                                        public void done(ErrorCodeList errorCodeList) {
                                            if (!errorCodeList.getCauses().isEmpty()) {
                                                logger.warn(String.format("failed to saveEncryptAfterCreateImageCache: %s", errorCodeList.getCauses().get(0)));
                                            }
                                            completion.success(inv);
                                            chain.next();
                                        }
                                    });
                                }
                            });

                            error(new FlowErrorHandler(completion, chain) {
                                @Override
                                public void handle(ErrorCode errCode, Map data) {
                                    completion.fail(errCode);
                                    chain.next();
                                }
                            });
                        }
                    }).start();
                }

                private void checkEncryptImageCache(ImageCacheInventory inventory, final SyncTaskChain chain) {
                    List<AfterCreateImageCacheExtensionPoint> extensionList = pluginRgty.getExtensionList(AfterCreateImageCacheExtensionPoint.class);

                    if (extensionList.isEmpty()) {
                        completion.success(inventory);
                        chain.next();
                        return;
                    }

                    extensionList.forEach(ext -> ext.checkEncryptImageCache(hostUuid, inventory, new Completion(chain) {
                        @Override
                        public void success() {
                            completion.success(inventory);
                            chain.next();
                        }

                        @Override
                        public void fail(ErrorCode errorCode) {
                            completion.fail(errorCode);
                            chain.next();
                        }
                    }));
                }

                @Override
                public void run(final SyncTaskChain chain) {
                    SimpleQuery<ImageCacheVO> q = dbf.createQuery(ImageCacheVO.class);
                    q.add(ImageCacheVO_.primaryStorageUuid, Op.EQ, self.getUuid());
                    q.add(ImageCacheVO_.imageUuid, Op.EQ, image.getUuid());
                    q.add(ImageCacheVO_.installUrl, Op.LIKE, String.format("%%hostUuid://%s%%", hostUuid));
                    ImageCacheVO cache = q.find();
                    if (cache == null) {
                        doDownload(chain);
                        return;
                    }

                    LocalStorageUtils.InstallPath path = new LocalStorageUtils.InstallPath();
                    path.fullPath = cache.getInstallUrl();
                    final String installPath = path.disassemble().installPath;
                    CheckBitsCmd cmd = new CheckBitsCmd();
                    cmd.path = installPath;

                    httpCall(CHECK_BITS_PATH, hostUuid, cmd, CheckBitsRsp.class, new ReturnValueCompletion<CheckBitsRsp>(completion, chain) {
                        @Override
                        public void success(CheckBitsRsp rsp) {
                            if (rsp.existing) {
                                logger.debug(String.format("found image[uuid: %s, name: %s] in the image cache of local primary storage[uuid:%s, installPath: %s]",
                                        image.getUuid(), image.getName(), self.getUuid(), installPath));

                                ImageCacheInventory inv = ImageCacheInventory.valueOf(cache);
                                inv.setInstallUrl(installPath);

                                checkEncryptImageCache(inv, chain);
                                return;
                            }

                            // the image is removed on the host
                            // delete the cache object and re-download it
                            SimpleQuery<ImageCacheVO> q = dbf.createQuery(ImageCacheVO.class);
                            q.add(ImageCacheVO_.primaryStorageUuid, Op.EQ, self.getUuid());
                            q.add(ImageCacheVO_.imageUuid, Op.EQ, image.getUuid());
                            q.add(ImageCacheVO_.installUrl, Op.LIKE, String.format("%%hostUuid://%s%%", hostUuid));
                            ImageCacheVO cvo = q.find();

                            ReleasePrimaryStorageSpaceMsg rmsg = new ReleasePrimaryStorageSpaceMsg();
                            rmsg.setDiskSize(cvo.getSize());
                            rmsg.setPrimaryStorageUuid(cvo.getPrimaryStorageUuid());
                            rmsg.setAllocatedInstallUrl(cvo.getInstallUrl());
                            bus.makeTargetServiceIdByResourceUuid(rmsg, PrimaryStorageConstant.SERVICE_ID, cvo.getPrimaryStorageUuid());
                            bus.send(rmsg);

                            dbf.remove(cvo);
                            doDownload(chain);
                        }

                        @Override
                        public void fail(ErrorCode errorCode) {
                            completion.fail(errorCode);
                            chain.next();
                        }
                    });
                }

                @Override
                public String getName() {
                    return getSyncSignature();
                }
            });
        }
    }

    private void createTemporaryRootVolume(InstantiateTemporaryRootVolumeFromTemplateOnPrimaryStorageMsg msg, final ReturnValueCompletion<InstantiateVolumeOnPrimaryStorageReply> completion) {
        final VolumeInventory volume = msg.getVolume();
        volume.setInstallPath(makeTemporaryRootVolumeInstallUrl(volume, msg.getOriginVolumeUuid()));
        createRootVolume(msg, completion);
    }

    private void createRootVolume(final InstantiateRootVolumeFromTemplateOnPrimaryStorageMsg msg, final ReturnValueCompletion<InstantiateVolumeOnPrimaryStorageReply> completion) {
        final ImageSpec ispec = msg.getTemplateSpec();
        final ImageInventory image = ispec.getInventory();

        if (!ImageMediaType.RootVolumeTemplate.toString().equals(image.getMediaType())) {
            createEmptyVolume(msg.getVolume(), msg.getDestHost().getUuid(), new ReturnValueCompletion<VolumeStats>(completion) {
                @Override
                public void success(VolumeStats returnValue) {
                    InstantiateVolumeOnPrimaryStorageReply r = new InstantiateVolumeOnPrimaryStorageReply();
                    VolumeInventory vol = msg.getVolume();
                    vol.setInstallPath(returnValue.getInstallPath());
                    vol.setActualSize(returnValue.getActualSize());
                    vol.setFormat(VolumeConstant.VOLUME_FORMAT_QCOW2);
                    r.setVolume(vol);
                    completion.success(r);
                }

                @Override
                public void fail(ErrorCode errorCode) {
                    completion.fail(errorCode);
                }
            });

            return;
        }

        final VolumeInventory volume = msg.getVolume();
        final String hostUuid = msg.getDestHost().getUuid();

        FlowChain chain = FlowChainBuilder.newShareFlowChain();
        chain.setName(String.format("kvm-localstorage-create-root-volume-from-image-%s", image.getUuid()));
        chain.then(new ShareFlow() {
            String pathInCache = makeCachedImageInstallUrl(image);
            String installPath = StringUtils.isNotEmpty(volume.getInstallPath()) ? volume.getInstallPath() :
                    makeRootVolumeInstallUrl(volume) ;
            Long actualSize;

            @Override
            public void setup() {
                flow(new NoRollbackFlow() {
                    String __name__ = "download-image-to-cache";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        DownloadVolumeTemplateToPrimaryStorageMsg dmsg = new DownloadVolumeTemplateToPrimaryStorageMsg();
                        dmsg.setTemplateSpec(msg.getTemplateSpec());
                        dmsg.setHostUuid(msg.getDestHost().getUuid());
                        dmsg.setPrimaryStorageUuid(msg.getPrimaryStorageUuid());
                        bus.makeTargetServiceIdByResourceUuid(dmsg, PrimaryStorageConstant.SERVICE_ID, dmsg.getPrimaryStorageUuid());
                        bus.send(dmsg, new CloudBusCallBack(trigger) {
                            @Override
                            public void run(MessageReply reply) {
                                if (!reply.isSuccess()) {
                                    trigger.fail(reply.getError());
                                    return;
                                }

                                trigger.next();
                            }
                        });
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "create-template-from-cache";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        CreateVolumeFromCacheCmd cmd = new CreateVolumeFromCacheCmd();
                        cmd.setInstallUrl(installPath);
                        cmd.setTemplatePathInCache(pathInCache);
                        cmd.setVolumeUuid(volume.getUuid());
                        if (image.getSize() < volume.getSize()) {
                            cmd.setVirtualSize(volume.getSize());
                        }

                        httpCall(CREATE_VOLUME_FROM_CACHE_PATH, hostUuid, cmd, CreateVolumeFromCacheRsp.class, new ReturnValueCompletion<CreateVolumeFromCacheRsp>(trigger) {
                            @Override
                            public void success(CreateVolumeFromCacheRsp returnValue) {
                                actualSize = returnValue.actualSize;
                                if (returnValue.size != null) {
                                    volume.setSize(returnValue.size);
                                }

                                trigger.next();
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                trigger.fail(errorCode);
                            }
                        });
                    }
                });

                done(new FlowDoneHandler(completion) {
                    @Override
                    public void handle(Map data) {
                        InstantiateVolumeOnPrimaryStorageReply reply = new InstantiateVolumeOnPrimaryStorageReply();
                        volume.setInstallPath(installPath);
                        volume.setActualSize(actualSize);
                        reply.setVolume(volume);
                        completion.success(reply);
                    }
                });

                error(new FlowErrorHandler(completion) {
                    @Override
                    public void handle(ErrorCode errCode, Map data) {
                        completion.fail(errCode);
                    }
                });
            }
        }).start();
    }

    @Override
    public void deleteBits(final String path, final String hostUuid, final Completion completion) {
        deleteBits(path, hostUuid, false, completion);
    }

    public void deleteBits(final String path, final String hostUuid, boolean dir, final Completion completion) {
        DeleteBitsCmd cmd = new DeleteBitsCmd();
        cmd.setPath(path);
        cmd.setHostUuid(hostUuid);

        String deletePath = dir ? DELETE_DIR_PATH : DELETE_BITS_PATH;

        httpCall(deletePath, hostUuid, cmd, DeleteBitsRsp.class, new ReturnValueCompletion<DeleteBitsRsp>(completion) {
            @Override
            public void success(DeleteBitsRsp returnValue) {
                if (returnValue.getAvailableCapacity() == null || returnValue.getTotalCapacity() == null) {
                    logger.warn("Deleting bits is successful, " +
                            "but getting capacity is failed, " +
                            "Please check if the storage has been detach from cluster");
                }
                completion.success();
            }

            @Override
            public void fail(ErrorCode errorCode) {
                if (!errorCode.isError(HostErrors.OPERATION_FAILURE_GC_ELIGIBLE)) {
                    completion.fail(errorCode);
                    return;
                }
                if (errorCode.isError(VolumeErrors.VOLUME_IN_USE)) {
                    logger.debug(String.format("unable to delete path:%s right now, skip this GC job because it's in use", path));
                    completion.fail(errorCode);
                    return;
                }

                LocalStorageDeleteBitsGC gc = new LocalStorageDeleteBitsGC();
                gc.isDir = dir;
                gc.primaryStorageUuid = self.getUuid();
                gc.hostUuid = hostUuid;
                gc.installPath = path;
                gc.NAME = String.format("gc-local-storage-%s-delete-bits-on-host-%s", self.getUuid(), hostUuid);
                gc.submit();

                completion.success();
            }
        });
    }

    @Override
    void handle(final DeleteVolumeOnPrimaryStorageMsg msg, final ReturnValueCompletion<DeleteVolumeOnPrimaryStorageReply> completion) {
        final DeleteVolumeOnPrimaryStorageReply dreply = new DeleteVolumeOnPrimaryStorageReply();
        final String hostUuid = getHostUuidByResourceUuid(msg.getVolume().getUuid(), VolumeVO.class.getSimpleName());

        boolean dir = msg.getVolume().getType().equals(VolumeType.Memory.toString());

        deleteBits(msg.getVolume().getInstallPath(), hostUuid, dir, new Completion(completion) {
            @Override
            public void success() {
                completion.success(dreply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(final DownloadDataVolumeToPrimaryStorageMsg msg, final ReturnValueCompletion<DownloadDataVolumeToPrimaryStorageReply> completion) {
        BackupStorageVO bsvo = dbf.findByUuid(msg.getBackupStorageRef().getBackupStorageUuid(), BackupStorageVO.class);
        LocalStorageBackupStorageMediator m = localStorageFactory.getBackupStorageMediator(KVMConstant.KVM_HYPERVISOR_TYPE, bsvo.getType());
        String installPath;
        if (msg instanceof DownloadTemporaryDataVolumeToPrimaryStorageMsg) {
            String originVolumeUuid = ((DownloadTemporaryDataVolumeToPrimaryStorageMsg) msg).getOriginVolumeUuid();
            installPath = makeTemporaryDataVolumeInstallUrl(msg.getVolumeUuid(), originVolumeUuid);
        } else {
            installPath = makeDataVolumeInstallUrl(msg.getVolumeUuid());
        }
        m.downloadBits(getSelfInventory(), BackupStorageInventory.valueOf(bsvo), msg.getBackupStorageRef().getInstallPath(), installPath, msg.getHostUuid(), true, new Completion(completion) {
            @Override
            public void success() {
                DownloadDataVolumeToPrimaryStorageReply reply = new DownloadDataVolumeToPrimaryStorageReply();
                reply.setFormat(msg.getImage().getFormat());
                reply.setInstallPath(installPath);
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(GetInstallPathForDataVolumeDownloadMsg msg, ReturnValueCompletion<GetInstallPathForDataVolumeDownloadReply> completion) {
        GetInstallPathForDataVolumeDownloadReply reply = new GetInstallPathForDataVolumeDownloadReply();
        if (msg instanceof GetInstallPathForTemporaryDataVolumeDownloadMsg) {
            String originVolumeUuid = ((GetInstallPathForTemporaryDataVolumeDownloadMsg) msg).getOriginVolumeUuid();
            reply.setInstallPath(makeTemporaryDataVolumeInstallUrl(msg.getVolumeUuid(), originVolumeUuid));
        } else {
            reply.setInstallPath(makeDataVolumeInstallUrl(msg.getVolumeUuid()));
        }
        completion.success(reply);
    }

    @Override
    void handle(DeleteVolumeBitsOnPrimaryStorageMsg msg, final ReturnValueCompletion<DeleteVolumeBitsOnPrimaryStorageReply> completion) {
        String hostUuid = msg.getHostUuid() != null ? msg.getHostUuid() : getHostUuidByResourceUuid(msg.getBitsUuid(), msg.getBitsType());
        if (!Q.New(HostVO.class).eq(HostVO_.uuid, hostUuid).isExists()) {
            logger.warn(String.format("delete volume on host: %s, but it is not existed", hostUuid));
            completion.success(new DeleteVolumeBitsOnPrimaryStorageReply());
            return;
        }
        deleteBits(msg.getInstallPath(), hostUuid, msg.isFolder(), new Completion(completion) {
            @Override
            public void success() {
                DeleteVolumeBitsOnPrimaryStorageReply reply = new DeleteVolumeBitsOnPrimaryStorageReply();
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(final DeleteBitsOnPrimaryStorageMsg msg, ReturnValueCompletion<DeleteBitsOnPrimaryStorageReply> completion) {
        deleteBits(msg.getInstallPath(), msg.getHostUuid(), msg.isFolder(), new Completion(completion) {
            @Override
            public void success() {
                DeleteBitsOnPrimaryStorageReply reply = new DeleteBitsOnPrimaryStorageReply();
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(DownloadIsoToPrimaryStorageMsg msg, final ReturnValueCompletion<DownloadIsoToPrimaryStorageReply> completion) {
        ImageSpec ispec = msg.getIsoSpec();
        SimpleQuery<BackupStorageVO> q = dbf.createQuery(BackupStorageVO.class);
        q.add(BackupStorageVO_.uuid, Op.EQ, ispec.getSelectedBackupStorage().getBackupStorageUuid());
        BackupStorageVO bsvo = q.find();
        BackupStorageInventory bsinv = BackupStorageInventory.valueOf(bsvo);

        ImageCache cache = new ImageCache();
        cache.image = ispec.getInventory();
        cache.hostUuid = msg.getDestHostUuid();
        cache.primaryStorageInstallPath = makeCachedImageInstallUrl(ispec.getInventory());
        cache.backupStorage = bsinv;
        cache.backupStorageInstallPath = ispec.getSelectedBackupStorage().getInstallPath();
        cache.download(new ReturnValueCompletion<ImageCacheInventory>(completion) {
            @Override
            public void success(ImageCacheInventory returnValue) {
                DownloadIsoToPrimaryStorageReply reply = new DownloadIsoToPrimaryStorageReply();
                reply.setInstallPath(returnValue.getInstallUrl());
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(DeleteIsoFromPrimaryStorageMsg msg, ReturnValueCompletion<DeleteIsoFromPrimaryStorageReply> completion) {
        // The ISO is in the image cache, no need to delete it
        DeleteIsoFromPrimaryStorageReply reply = new DeleteIsoFromPrimaryStorageReply();
        completion.success(reply);
    }

    @Override
    protected void handle(InitPrimaryStorageOnHostConnectedMsg msg, final ReturnValueCompletion<PhysicalCapacityUsage> completion) {
        InitCmd cmd = new InitCmd();
        cmd.setHostUuid(msg.getHostUuid());
        cmd.setPath(self.getUrl());
        cmd.setInitFilePath(makeInitializedFilePath());

        httpCall(INIT_PATH, msg.getHostUuid(), cmd, true, InitRsp.class,
                new ReturnValueCompletion<InitRsp>(completion) {
                    @Override
                    public void success(InitRsp rsp) {
                        LocalStoragePhysicalCapacityUsage usage = new LocalStoragePhysicalCapacityUsage();
                        usage.totalPhysicalSize = rsp.getTotalCapacity();
                        usage.availablePhysicalSize = rsp.getAvailableCapacity();
                        usage.localStorageUsedSize = rsp.getLocalStorageUsedCapacity();
                        completion.success(usage);
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        completion.fail(errorCode);
                    }
                });
    }

    @Override
    void handle(final CheckSnapshotMsg msg, final String hostUuid, final Completion completion) {
        Tuple tuple = Q.New(VolumeVO.class)
                .select(VolumeVO_.vmInstanceUuid, VolumeVO_.installPath, VolumeVO_.rootImageUuid)
                .eq(VolumeVO_.uuid, msg.getVolumeUuid())
                .findTuple();

        CheckSnapshotOnHypervisorMsg hmsg = new CheckSnapshotOnHypervisorMsg();
        hmsg.setHostUuid(hostUuid);
        hmsg.setVolumeChainToCheck(msg.getVolumeChainToCheck());
        hmsg.setVolumeUuid(msg.getVolumeUuid());
        hmsg.setVmUuid((String) tuple.get(0));
        hmsg.setCurrentInstallPath((String) tuple.get(1));
        hmsg.setPrimaryStorageUuid(self.getUuid());
        if (tuple.get(2) != null) {
            String imageCacheInstallPath = getImageCacheInstallPath((String) tuple.get(2), hostUuid);
            if (imageCacheInstallPath != null) {
                hmsg.getExcludeInstallPaths().add(imageCacheInstallPath);
            }
        }
        bus.makeTargetServiceIdByResourceUuid(hmsg, HostConstant.SERVICE_ID, hmsg.getHostUuid());
        bus.send(hmsg, new CloudBusCallBack(completion) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    completion.fail(reply.getError());
                    return;
                }

                completion.success();
            }
        });
    }

    private String getImageCacheInstallPath(String imageUuid, String hostUuid) {
        SimpleQuery<ImageCacheVO> q = dbf.createQuery(ImageCacheVO.class);
        q.add(ImageCacheVO_.primaryStorageUuid, Op.EQ, self.getUuid());
        q.add(ImageCacheVO_.imageUuid, Op.EQ, imageUuid);
        q.add(ImageCacheVO_.installUrl, Op.LIKE, String.format("%%hostUuid://%s%%", hostUuid));
        ImageCacheVO cache = q.find();
        if (cache == null) {
            return null;
        }

        LocalStorageUtils.InstallPath path = new LocalStorageUtils.InstallPath();
        path.fullPath = cache.getInstallUrl();
        return path.disassemble().installPath;
    }

    @Override
    void handle(final TakeSnapshotMsg msg, final String hostUuid, final ReturnValueCompletion<TakeSnapshotReply> completion) {
        final VolumeSnapshotInventory sp = msg.getStruct().getCurrent();
        VolumeInventory vol = VolumeInventory.valueOf(dbf.findByUuid(sp.getVolumeUuid(), VolumeVO.class));

        TakeSnapshotOnHypervisorMsg hmsg = new TakeSnapshotOnHypervisorMsg();
        hmsg.setHostUuid(hostUuid);
        hmsg.setVmUuid(vol.getVmInstanceUuid());
        hmsg.setVolume(vol);
        hmsg.setSnapshotName(msg.getStruct().getCurrent().getUuid());
        hmsg.setFullSnapshot(msg.getStruct().isFullSnapshot());
        String installPath = makeSnapshotInstallPath(vol, sp);
        hmsg.setInstallPath(installPath);
        bus.makeTargetServiceIdByResourceUuid(hmsg, HostConstant.SERVICE_ID, hostUuid);
        bus.send(hmsg, new CloudBusCallBack(completion) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    completion.fail(reply.getError());
                    return;
                }

                TakeSnapshotOnHypervisorReply treply = (TakeSnapshotOnHypervisorReply) reply;
                sp.setSize(treply.getSize());
                sp.setPrimaryStorageUuid(self.getUuid());
                sp.setPrimaryStorageInstallPath(treply.getSnapshotInstallPath());
                sp.setType(VolumeSnapshotConstant.HYPERVISOR_SNAPSHOT_TYPE.toString());

                TakeSnapshotReply ret = new TakeSnapshotReply();
                ret.setNewVolumeInstallPath(treply.getNewVolumeInstallPath());
                ret.setInventory(sp);

                completion.success(ret);
            }
        });
    }

    @Override
    void handle(final UndoSnapshotCreationOnPrimaryStorageMsg msg, final String hostUuid, final ReturnValueCompletion<UndoSnapshotCreationOnPrimaryStorageReply> completion) {
        CommitVolumeOnHypervisorMsg hmsg = new CommitVolumeOnHypervisorMsg();
        hmsg.setHostUuid(hostUuid);
        hmsg.setVmUuid(msg.getVmUuid());
        hmsg.setVolume(msg.getVolume());
        hmsg.setSrcPath(msg.getSrcPath());
        hmsg.setDstPath(msg.getDstPath());
        bus.makeTargetServiceIdByResourceUuid(hmsg, HostConstant.SERVICE_ID, hostUuid);
        bus.send(hmsg, new CloudBusCallBack(completion) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    completion.fail(reply.getError());
                    return;
                }

                UndoSnapshotCreationOnPrimaryStorageReply ret = new UndoSnapshotCreationOnPrimaryStorageReply();
                CommitVolumeOnHypervisorReply treply = (CommitVolumeOnHypervisorReply) reply;
                ret.setSize(treply.getSize());
                ret.setNewVolumeInstallPath(treply.getNewVolumeInstallPath());
                completion.success(ret);
            }
        });
    }

    @Override
    void handle(final DeleteSnapshotOnPrimaryStorageMsg msg, final String hostUuid, final ReturnValueCompletion<DeleteSnapshotOnPrimaryStorageReply> completion) {
        final DeleteSnapshotOnPrimaryStorageReply reply = new DeleteSnapshotOnPrimaryStorageReply();
        deleteBits(msg.getSnapshot().getPrimaryStorageInstallPath(), hostUuid, new Completion(completion) {
            @Override
            public void success() {
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(RevertVolumeFromSnapshotOnPrimaryStorageMsg msg, String hostUuid, final ReturnValueCompletion<RevertVolumeFromSnapshotOnPrimaryStorageReply> completion) {
        VolumeSnapshotInventory sp = msg.getSnapshot();
        RevertVolumeFromSnapshotCmd cmd = new RevertVolumeFromSnapshotCmd();
        cmd.setSnapshotInstallPath(sp.getPrimaryStorageInstallPath());

        httpCall(REVERT_SNAPSHOT_PATH, hostUuid, cmd, RevertVolumeFromSnapshotRsp.class, new ReturnValueCompletion<RevertVolumeFromSnapshotRsp>(completion) {
            @Override
            public void success(RevertVolumeFromSnapshotRsp rsp) {
                RevertVolumeFromSnapshotOnPrimaryStorageReply ret = new RevertVolumeFromSnapshotOnPrimaryStorageReply();
                ret.setNewVolumeInstallPath(rsp.getNewVolumeInstallPath());
                ret.setSize(rsp.getSize());

                completion.success(ret);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(ReInitRootVolumeFromTemplateOnPrimaryStorageMsg msg, String hostUuid, final ReturnValueCompletion<ReInitRootVolumeFromTemplateOnPrimaryStorageReply> completion) {
        ReInitRootVolumeFromTemplateOnPrimaryStorageReply reply = new ReInitRootVolumeFromTemplateOnPrimaryStorageReply();

        FlowChain chain = new SimpleFlowChain();
        chain.setName("re-init-root-volume-on-primary-storage");
        chain.then(new NoRollbackFlow() {
            @Override
            public void run(FlowTrigger trigger, Map data) {
                ReinitImageCmd cmd = new ReinitImageCmd();
                if (msg.getVolume().getRootImageUuid() == null) {
                    completion.fail(operr("root image has been deleted, cannot reimage now"));
                    return;
                }

                if (!dbf.isExist(msg.getVolume().getRootImageUuid(), ImageVO.class)) {
                    completion.fail(operr("root image has been deleted, cannot reimage now"));
                    return;
                }
                cmd.imagePath = makeCachedImageInstallUrlFromImageUuidForTemplate(msg.getVolume().getRootImageUuid());
                cmd.volumePath = makeRootVolumeInstallUrl(msg.getVolume());

                httpCall(REINIT_IMAGE_PATH, hostUuid, cmd, ReinitImageRsp.class, new ReturnValueCompletion<ReinitImageRsp>(completion) {
                    @Override
                    public void success(ReinitImageRsp rsp) {
                        reply.setNewVolumeInstallPath(rsp.getNewVolumeInstallPath());
                        trigger.next();
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        trigger.fail(errorCode);
                    }
                });
            }
        }).done(new FlowDoneHandler(msg) {
            @Override
            public void handle(Map data) {
                completion.success(reply);
            }
        }).error(new FlowErrorHandler(msg) {
            @Override
            public void handle(ErrorCode errCode, Map data) {
                completion.fail(errCode);
            }
        }).start();
    }

    @Override
    void handle(BackupVolumeSnapshotFromPrimaryStorageToBackupStorageMsg msg, String hostUuid, final ReturnValueCompletion<BackupVolumeSnapshotFromPrimaryStorageToBackupStorageReply> completion) {
        VolumeSnapshotInventory sp = msg.getSnapshot();
        LocalStorageBackupStorageMediator m = localStorageFactory.getBackupStorageMediator(KVMConstant.KVM_HYPERVISOR_TYPE, msg.getBackupStorage().getType());
        BackupStorageAskInstallPathMsg bmsg = new BackupStorageAskInstallPathMsg();
        bmsg.setImageMediaType(VolumeSnapshotVO.class.getSimpleName());
        bmsg.setBackupStorageUuid(msg.getBackupStorage().getUuid());
        bmsg.setImageUuid(sp.getUuid());
        bus.makeTargetServiceIdByResourceUuid(bmsg, BackupStorageConstant.SERVICE_ID, msg.getBackupStorage().getUuid());
        MessageReply br = bus.call(bmsg);
        if (!br.isSuccess()) {
            completion.fail(br.getError());
            return;
        }

        final String installPath = ((BackupStorageAskInstallPathReply) br).getInstallPath();

        m.uploadBits(sp.getUuid(), getSelfInventory(), msg.getBackupStorage(), installPath, sp.getPrimaryStorageInstallPath(), hostUuid, new ReturnValueCompletion<String>(completion) {
            @Override
            public void success(String installPath) {
                BackupVolumeSnapshotFromPrimaryStorageToBackupStorageReply reply = new BackupVolumeSnapshotFromPrimaryStorageToBackupStorageReply();
                reply.setBackupStorageInstallPath(installPath);
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(CreateVolumeFromVolumeSnapshotOnPrimaryStorageMsg msg, String hostUuid, final ReturnValueCompletion<CreateVolumeFromVolumeSnapshotOnPrimaryStorageReply> completion) {
        if (msg.hasSystemTag(VolumeSystemTags.FAST_CREATE::isMatch)) {
            createIncrementalVolumeFromSnapshot(msg.getSnapshot(), msg.getVolumeUuid(), hostUuid, completion);
        } else {
            createNormalVolumeFromSnapshot(msg.getSnapshot(), msg.getVolumeUuid(), hostUuid, completion);
        }
    }

    private void createNormalVolumeFromSnapshot(VolumeSnapshotInventory sp, String volumeUuid, String hostUuid, final ReturnValueCompletion<CreateVolumeFromVolumeSnapshotOnPrimaryStorageReply> completion) {
        final CreateVolumeFromVolumeSnapshotOnPrimaryStorageReply reply = new CreateVolumeFromVolumeSnapshotOnPrimaryStorageReply();

        final String installPath = makeDataVolumeInstallUrl(volumeUuid);
        MergeSnapshotCmd cmd = new MergeSnapshotCmd();
        cmd.setVolumeUuid(sp.getVolumeUuid());
        cmd.setSnapshotInstallPath(sp.getPrimaryStorageInstallPath());
        cmd.setWorkspaceInstallPath(installPath);

        httpCall(MERGE_SNAPSHOT_PATH, hostUuid, cmd, MergeSnapshotRsp.class, new ReturnValueCompletion<MergeSnapshotRsp>(completion) {
            @Override
            public void success(MergeSnapshotRsp rsp) {
                reply.setActualSize(rsp.actualSize);
                reply.setSize(rsp.size);
                reply.setInstallPath(installPath);
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    private void createIncrementalVolumeFromSnapshot(VolumeSnapshotInventory sp, String volumeUuid, String hostUuid, final ReturnValueCompletion<CreateVolumeFromVolumeSnapshotOnPrimaryStorageReply> completion) {
        final CreateVolumeFromVolumeSnapshotOnPrimaryStorageReply reply = new CreateVolumeFromVolumeSnapshotOnPrimaryStorageReply();

        final String installPath = makeDataVolumeInstallUrl(volumeUuid);
        CreateVolumeWithBackingCmd cmd = new CreateVolumeWithBackingCmd();
        cmd.volumeUuid = volumeUuid;
        cmd.installPath = installPath;
        cmd.templatePathInCache = sp.getPrimaryStorageInstallPath();
        httpCall(CREATE_VOLUME_WITH_BACKING_PATH, hostUuid, cmd, CreateVolumeWithBackingRsp.class, new ReturnValueCompletion<CreateVolumeWithBackingRsp>(completion) {
            @Override
            public void success(CreateVolumeWithBackingRsp rsp) {
                reply.setActualSize(rsp.actualSize);
                reply.setSize(rsp.size);
                reply.setInstallPath(installPath);
                reply.setIncremental(true);
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void stream(VolumeSnapshotInventory from, VolumeInventory to, boolean fullRebase, String hostUuid, final Completion completion) {
        boolean offline = true;
        VolumeInventory volume = to;
        VolumeSnapshotInventory sp = from != null ? from : new VolumeSnapshotInventory();
        fullRebase |= from == null;

        if (volume.getType().equals(VolumeType.Memory.toString())) {
            completion.success();
            return;
        }

        if (volume.getVmInstanceUuid() != null) {
            SimpleQuery<VmInstanceVO> q = dbf.createQuery(VmInstanceVO.class);
            q.select(VmInstanceVO_.state);
            q.add(VmInstanceVO_.uuid, Op.EQ, volume.getVmInstanceUuid());
            VmInstanceState state = q.findValue();

            if (state != VmInstanceState.Stopped && state != VmInstanceState.Running
                    && state != VmInstanceState.Destroyed && state != VmInstanceState.Paused) {
                throw new OperationFailureException(operr("the volume[uuid;%s] is attached to a VM[uuid:%s] which is in state of %s, cannot do the snapshot merge",
                        volume.getUuid(), volume.getVmInstanceUuid(), state));
            }

            offline = (state == VmInstanceState.Stopped || state == VmInstanceState.Destroyed);
        }

        if (offline) {
            OfflineMergeSnapshotCmd cmd = new OfflineMergeSnapshotCmd();
            cmd.setFullRebase(fullRebase);
            cmd.setSrcPath(sp.getPrimaryStorageInstallPath());
            cmd.setDestPath(volume.getInstallPath());

            httpCall(OFFLINE_MERGE_PATH, hostUuid, cmd, OfflineMergeSnapshotRsp.class, new ReturnValueCompletion<OfflineMergeSnapshotRsp>(completion) {
                @Override
                public void success(OfflineMergeSnapshotRsp returnValue) {
                    completion.success();
                }

                @Override
                public void fail(ErrorCode errorCode) {
                    completion.fail(errorCode);
                }
            });
        } else {
            MergeVolumeSnapshotOnKvmMsg kmsg = new MergeVolumeSnapshotOnKvmMsg();
            kmsg.setFullRebase(fullRebase);
            kmsg.setHostUuid(hostUuid);
            kmsg.setFrom(sp);
            kmsg.setTo(volume);
            bus.makeTargetServiceIdByResourceUuid(kmsg, HostConstant.SERVICE_ID, hostUuid);
            bus.send(kmsg, new CloudBusCallBack(completion) {
                @Override
                public void run(MessageReply reply) {
                    if (reply.isSuccess()) {
                        completion.success();
                    } else {
                        completion.fail(reply.getError());
                    }
                }
            });
        }
    }

    @Override
    void handle(LocalStorageCreateEmptyVolumeMsg msg, final ReturnValueCompletion<LocalStorageCreateEmptyVolumeReply> completion) {
        createEmptyVolumeWithBackingFile(msg.getVolume(), msg.getHostUuid(), msg.getBackingFile(), new ReturnValueCompletion<VolumeStats>(completion) {
            @Override
            public void success(VolumeStats returnValue) {
                LocalStorageCreateEmptyVolumeReply reply = new LocalStorageCreateEmptyVolumeReply();
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(LocalStorageDirectlyDeleteBitsMsg msg, String hostUuid, final ReturnValueCompletion<LocalStorageDirectlyDeleteBitsReply> completion) {
        deleteBits(msg.getPath(), hostUuid, new Completion(completion) {
            @Override
            public void success() {
                completion.success(new LocalStorageDirectlyDeleteBitsReply());
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(CreateTemporaryVolumeFromSnapshotMsg msg, final String hostUuid, final ReturnValueCompletion<CreateTemporaryVolumeFromSnapshotReply> completion) {
        final String workSpaceInstallPath = makeSnapshotWorkspacePath(msg.getImageUuid());

        VolumeSnapshotInventory sp = msg.getSnapshot();
        MergeSnapshotCmd cmd = new MergeSnapshotCmd();
        cmd.setVolumeUuid(sp.getVolumeUuid());
        cmd.setSnapshotInstallPath(sp.getPrimaryStorageInstallPath());
        cmd.setWorkspaceInstallPath(workSpaceInstallPath);

        httpCall(MERGE_SNAPSHOT_PATH, hostUuid, cmd, MergeSnapshotRsp.class, new ReturnValueCompletion<MergeSnapshotRsp>(completion) {
            @Override
            public void success(MergeSnapshotRsp rsp) {
                CreateTemporaryVolumeFromSnapshotReply reply = new CreateTemporaryVolumeFromSnapshotReply();
                reply.setInstallPath(workSpaceInstallPath);
                reply.setSize(rsp.size);
                reply.setActualSize(rsp.actualSize);
                reply.setHostUuid(hostUuid);
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                deleteBits(workSpaceInstallPath, hostUuid, new NopeCompletion());
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(SyncVolumeSizeOnPrimaryStorageMsg msg, String hostUuid, final ReturnValueCompletion<SyncVolumeSizeOnPrimaryStorageReply> completion) {
        final SyncVolumeSizeOnPrimaryStorageReply reply = new SyncVolumeSizeOnPrimaryStorageReply();
        GetVolumeSizeCmd cmd = new GetVolumeSizeCmd();
        cmd.installPath = msg.getInstallPath();
        cmd.volumeUuid = msg.getVolumeUuid();
        cmd.storagePath = self.getUrl();

        KvmCommandSender sender = new KvmCommandSender(hostUuid);
        sender.send(cmd, GET_VOLUME_SIZE, new KvmCommandFailureChecker() {
            @Override
            public ErrorCode getError(KvmResponseWrapper wrapper) {
                GetVolumeSizeRsp rsp = wrapper.getResponse(GetVolumeSizeRsp.class);
                return rsp.isSuccess() ? null : operr("operation error, because:%s", rsp.getError());
            }
        }, new ReturnValueCompletion<KvmResponseWrapper>(completion) {
            @Override
            public void success(KvmResponseWrapper returnValue) {
                GetVolumeSizeRsp rsp = returnValue.getResponse(GetVolumeSizeRsp.class);
                reply.setActualSize(rsp.actualSize);
                reply.setSize(rsp.size);
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(EstimateVolumeTemplateSizeOnPrimaryStorageMsg msg, String huuid, ReturnValueCompletion<EstimateVolumeTemplateSizeOnPrimaryStorageReply> completion) {
        final EstimateVolumeTemplateSizeOnPrimaryStorageReply reply = new EstimateVolumeTemplateSizeOnPrimaryStorageReply();
        EstimateTemplateSizeCmd cmd = new EstimateTemplateSizeCmd();
        cmd.volumePath = msg.getInstallPath();

        httpCall(ESTIMATE_TEMPLATE_SIZE_PATH, huuid, cmd, EstimateTemplateSizeRsp.class, new ReturnValueCompletion<EstimateTemplateSizeRsp>(completion) {
            @Override
            public void success(EstimateTemplateSizeRsp rsp) {
                reply.setSize(rsp.size);
                reply.setActualSize(rsp.actualSize);
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(BatchSyncVolumeSizeOnPrimaryStorageMsg msg, String hostUuid, ReturnValueCompletion<BatchSyncVolumeSizeOnPrimaryStorageReply> completion) {
        final BatchSyncVolumeSizeOnPrimaryStorageReply reply = new BatchSyncVolumeSizeOnPrimaryStorageReply();
        GetBatchVolumeSizeCmd cmd = new GetBatchVolumeSizeCmd();
        cmd.volumeUuidInstallPaths = msg.getVolumeUuidInstallPaths();

        httpCall(BATCH_GET_VOLUME_SIZE, msg.getHostUuid(), cmd, GetBatchVolumeSizeRsp.class, new ReturnValueCompletion<GetBatchVolumeSizeRsp>(completion) {
            @Override
            public void success(GetBatchVolumeSizeRsp rsp) {
                reply.setActualSizes(rsp.actualSizes);
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(final UploadBitsFromLocalStorageToBackupStorageMsg msg, String hostUuid, final ReturnValueCompletion<UploadBitsFromLocalStorageToBackupStorageReply> completion) {
        final BackupStorageVO bs = dbf.findByUuid(msg.getBackupStorageUuid(), BackupStorageVO.class);
        BackupStorageInventory bsinv = BackupStorageInventory.valueOf(bs);

        LocalStorageBackupStorageMediator m = localStorageFactory.getBackupStorageMediator(KVMConstant.KVM_HYPERVISOR_TYPE, bs.getType());
        m.uploadBits(msg.getImageUuid(), getSelfInventory(), bsinv, msg.getBackupStorageInstallPath(), msg.getPrimaryStorageInstallPath(),
                hostUuid, new ReturnValueCompletion<String>(completion) {
                    @Override
                    public void success(String installPath) {
                        UploadBitsFromLocalStorageToBackupStorageReply reply = new UploadBitsFromLocalStorageToBackupStorageReply();
                        reply.setBackupStorageInstallPath(installPath);
                        bus.reply(msg, reply);
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        logger.warn(String.format("failed to upload template[%s] from local primary storage[uuid: %s] to the backup storage[uuid: %s, path: %s]",
                                msg.getPrimaryStorageInstallPath(), self.getUuid(), bs.getUuid(), msg.getBackupStorageInstallPath()));
                        completion.fail(errorCode);
                    }
                });
    }

    @Override
    void handle(final GetVolumeRootImageUuidFromPrimaryStorageMsg msg, String hostUuid, final ReturnValueCompletion<GetVolumeRootImageUuidFromPrimaryStorageReply> completion) {
        GetVolumeBaseImagePathCmd cmd = new GetVolumeBaseImagePathCmd();
        cmd.volumeInstallDir = makeVolumeInstallDir(msg.getVolume());
        cmd.volumeInstallPath = msg.getVolume().getInstallPath();
        cmd.imageCacheDir = getCachedImageDir();
        cmd.volumeUuid = msg.getVolume().getUuid();
        cmd.storagePath = Q.New(PrimaryStorageVO.class)
                .eq(PrimaryStorageVO_.uuid, msg.getPrimaryStorageUuid())
                .select(PrimaryStorageVO_.url)
                .findValue();

        httpCall(GET_BASE_IMAGE_PATH, hostUuid, cmd, GetVolumeBaseImagePathRsp.class, new ReturnValueCompletion<GetVolumeBaseImagePathRsp>(completion) {
            @Override
            public void success(GetVolumeBaseImagePathRsp rsp) {
                GetVolumeRootImageUuidFromPrimaryStorageReply reply = new GetVolumeRootImageUuidFromPrimaryStorageReply();
                if (rsp.path != null) {
                    String rootImageUuid = new File(rsp.path).getName().split("\\.")[0];
                    reply.setImageUuid(rootImageUuid);
                }

                if (!CollectionUtils.isEmpty(rsp.otherPaths)) {
                    reply.setOtherImageUuids(rsp.otherPaths.stream().map(p -> new File(p).getName().split("\\.")[0]).collect(Collectors.toList()));
                }
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(GetVolumeBackingChainFromPrimaryStorageMsg msg, ReturnValueCompletion<GetVolumeBackingChainFromPrimaryStorageReply> completion) {
        GetVolumeBackingChainFromPrimaryStorageReply reply = new GetVolumeBackingChainFromPrimaryStorageReply();
        new While<>(msg.getRootInstallPaths()).each((installPath, compl) -> {
            GetBackingChainCmd cmd = new GetBackingChainCmd();
            cmd.installPath = installPath;
            cmd.volumeUuid = msg.getVolumeUuid();
            httpCall(GET_BACKING_CHAIN_PATH, msg.getHostUuid(), cmd, GetBackingChainRsp.class, new ReturnValueCompletion<GetBackingChainRsp>(compl) {
                @Override
                public void success(GetBackingChainRsp rsp) {
                    if (CollectionUtils.isEmpty(rsp.backingChain)) {
                        reply.putBackingChainInstallPath(installPath, Collections.emptyList());
                        reply.putBackingChainSize(installPath, 0L);
                    } else {
                        reply.putBackingChainInstallPath(installPath, rsp.backingChain);
                        reply.putBackingChainSize(installPath, rsp.totalSize);
                    }
                    compl.done();
                }

                @Override
                public void fail(ErrorCode errorCode) {
                    compl.addError(errorCode);
                    compl.done();
                }
            });
        }).run(new WhileDoneCompletion(msg) {
            @Override
            public void done(ErrorCodeList err) {
                if (!err.getCauses().isEmpty()) {
                    completion.fail(err.getCauses().get(0));
                } else {
                    completion.success(reply);
                }
            }
        });
    }

    @Override
    @MessageSafe
    void handleHypervisorSpecificMessage(LocalStorageHypervisorSpecificMessage msg) {
        bus.dealWithUnknownMessage((Message) msg);
    }

    @Override
    void downloadImageToCache(ImageInventory img, String hostUuid, final ReturnValueCompletion<String> completion) {
        DownloadVolumeTemplateToPrimaryStorageMsg dmsg = new DownloadVolumeTemplateToPrimaryStorageMsg();
        dmsg.setPrimaryStorageUuid(self.getUuid());
        dmsg.setHostUuid(hostUuid);
        ImageSpec imageSpec = new ImageSpec();
        imageSpec.setInventory(img);
        dmsg.setTemplateSpec(imageSpec);
        bus.makeTargetServiceIdByResourceUuid(dmsg, PrimaryStorageConstant.SERVICE_ID, dmsg.getPrimaryStorageUuid());
        bus.send(dmsg, new CloudBusCallBack(completion) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    completion.fail(reply.getError());
                    return;
                }

                completion.success(((DownloadVolumeTemplateToPrimaryStorageReply) reply).getImageCache().getInstallUrl());
            }
        });
    }

    @Override
    void handle(final LocalStorageDeleteImageCacheOnPrimaryStorageMsg msg, String hostUuid, final ReturnValueCompletion<DeleteImageCacheOnPrimaryStorageReply> completion) {
        FlowChain chain = FlowChainBuilder.newShareFlowChain();
        PrimaryStorageVO ps = dbf.findByUuid(msg.getPrimaryStorageUuid(), PrimaryStorageVO.class);
        if (ps == null) {
            logger.warn(String.format("ps [%s] cannot find, maybe it is deleted already.", msg.getPrimaryStorageUuid()));
            DeleteImageCacheOnPrimaryStorageReply reply = new DeleteImageCacheOnPrimaryStorageReply();
            completion.success(reply);
            return;
        }
        chain.setName(String.format("clean-up-image-cache-on-local-storage-%s", self.getUuid()));
        chain.then(new ShareFlow() {
            @Override
            public void setup() {
                flow(new NoRollbackFlow() {
                    String __name__ = "ensure-image-is-not-referenced";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        GetQCOW2ReferenceCmd cmd = new GetQCOW2ReferenceCmd();
                        cmd.searchingDir = self.getUrl();
                        cmd.path = msg.getInstallPath();
                        cmd.storagePath = ps.getUrl();

                        new KvmCommandSender(msg.getHostUuid()).send(cmd, GET_QCOW2_REFERENCE, new KvmCommandFailureChecker() {
                            @Override
                            public ErrorCode getError(KvmResponseWrapper wrapper) {
                                GetQCOW2ReferenceRsp rsp = wrapper.getResponse(GetQCOW2ReferenceRsp.class);
                                return rsp.isSuccess() ? null : operr("operation error, because:%s", rsp.getError());
                            }
                        }, new ReturnValueCompletion<KvmResponseWrapper>(trigger) {
                            @Override
                            public void success(KvmResponseWrapper w) {
                                GetQCOW2ReferenceRsp rsp = w.getResponse(GetQCOW2ReferenceRsp.class);
                                if (rsp.referencePaths == null || rsp.referencePaths.isEmpty()) {
                                    trigger.next();
                                } else {
                                    trigger.fail(inerr("[THIS IS A BUG NEEDED TO BE FIXED RIGHT NOW, PLEASE REPORT TO US ASAP] the image cache file[%s] is still referenced by" +
                                            " below QCOW2 files:\n%s", msg.getInstallPath(), StringUtils.join(rsp.referencePaths, "\n")));
                                }
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                trigger.fail(errorCode);
                            }
                        });
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "delete-local-image-cache-bits";
                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        deleteBits(PathUtil.parentFolder(msg.getInstallPath()), msg.getHostUuid(), true, new Completion(trigger) {
                            @Override
                            public void success() {
                                trigger.next();
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                trigger.fail(errorCode);
                            }
                        });
                    }
                });

                done(new FlowDoneHandler(completion) {
                    @Override
                    public void handle(Map data) {
                        DeleteImageCacheOnPrimaryStorageReply reply = new DeleteImageCacheOnPrimaryStorageReply();
                        completion.success(reply);
                    }
                });

                error(new FlowErrorHandler(completion) {
                    @Override
                    public void handle(ErrorCode errCode, Map data) {
                        completion.fail(errCode);
                    }
                });
            }
        }).start();
    }

    @Override
    public List<Flow> createMigrateBitsVolumeFlow(final MigrateBitsStruct struct) {
        List<Flow> flows = new ArrayList<Flow>();

        SimpleQuery<KVMHostVO> q = dbf.createQuery(KVMHostVO.class);
        q.select(KVMHostVO_.username, KVMHostVO_.password, KVMHostVO_.port);
        q.add(KVMHostVO_.uuid, Op.EQ, struct.getDestHostUuid());
        Tuple t = q.findTuple();

        final String destIp = localStorageFactory.getDestMigrationAddress(struct.getSrcHostUuid(), struct.getDestHostUuid());
        final String username = t.get(0, String.class);
        final String password = t.get(1, String.class);
        final int port = t.get(2, Integer.class);

        class Context {
            GetMd5Rsp getMd5Rsp;
            String baseImageCachePath;
            String rootVolumeUuid;
            Long baseImageCacheSize;
            String baseImageCacheMd5;
            ImageVO image;
            Boolean hasbackingfile = false;
        }
        final Context context = new Context();

        if (VolumeType.Root.toString().equals(struct.getVolume().getType())) {
            final boolean downloadImage;
            String imageUuid = struct.getVolume().getRootImageUuid();
            if (imageUuid != null) {
                context.image = dbf.findByUuid(imageUuid, ImageVO.class);
                downloadImage = !(context.image == null || context.image.getMediaType() == ImageMediaType.ISO || context.image.getStatus() == ImageStatus.Deleted);
            } else {
                downloadImage = false;
            }

            flows.add(new NoRollbackFlow() {
                String __name__ = "get-base-image-cache-of-root-volume";

                @Override
                public void run(final FlowTrigger trigger, Map data) {
                    GetVolumeBaseImagePathCmd cmd = new GetVolumeBaseImagePathCmd();
                    cmd.volumeInstallDir = makeVolumeInstallDir(struct.getVolume());
                    cmd.volumeInstallPath = struct.getVolume().getInstallPath();
                    cmd.imageCacheDir = getCachedImageDir();
                    cmd.volumeUuid = struct.getVolume().getUuid();
                    httpCall(GET_BASE_IMAGE_PATH, struct.getSrcHostUuid(), cmd, GetVolumeBaseImagePathRsp.class, new ReturnValueCompletion<GetVolumeBaseImagePathRsp>(trigger) {
                        @Override
                        public void success(GetVolumeBaseImagePathRsp rsp) {
                            if (rsp.path != null && isCachedImageUrl(rsp.path)) {
                                context.baseImageCachePath = rsp.path;
                                context.baseImageCacheSize = rsp.size;
                            } else if (rsp.path == null && !CollectionUtils.isEmpty(rsp.otherPaths)) {
                                // TODO handle snapshot image
                                context.baseImageCachePath = rsp.otherPaths.get(0);
                                context.baseImageCacheSize = 0L;
                            }

                            context.rootVolumeUuid = cmd.volumeUuid;
                            trigger.next();
                        }

                        @Override
                        public void fail(ErrorCode errorCode) {
                            logger.error(String.format("cannot get volume base image %s, skip and continue", errorCode.getDetails()));
                            trigger.next();
                        }
                    });
                }
            });

            if (downloadImage) {
                flows.add(new NoRollbackFlow() {
                    String __name__ = "download-base-image-to-dst-host";

                    @Override
                    public boolean skip(Map data) {
                        if (context.baseImageCachePath == null) {
                            logger.debug("no base image cache, skip this flow");
                            return true;
                        }
                        return false;
                    }

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        downloadImageToCache(ImageInventory.valueOf(context.image), struct.getDestHostUuid(), new ReturnValueCompletion<String>(trigger) {
                            @Override
                            public void success(String returnValue) {
                                trigger.next();
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                trigger.fail(errorCode);
                            }
                        });
                    }
                });
            } else {
                context.hasbackingfile = true;
                flows.add(new Flow() {
                    String __name__ = "reserve-capacity-for-base-image-cache-on-dst-host";

                    boolean s = false;

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        if (context.baseImageCachePath == null) {
                            logger.debug("no base image cache, skip this flow");
                            trigger.next();
                            return;
                        }

                        reserveCapacityOnHost(struct.getDestHostUuid(), context.baseImageCacheSize, self.getUuid());
                        s = true;
                        trigger.next();
                    }

                    @Override
                    public void rollback(FlowRollback trigger, Map data) {
                        if (s) {
                            returnStorageCapacityToHost(struct.getDestHostUuid(), context.baseImageCacheSize);
                        }
                        trigger.rollback();
                    }
                });

                flows.add(new NoRollbackFlow() {
                    String __name__ = "get-md5-of-base-image-cache";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        if (context.baseImageCachePath == null) {
                            logger.debug("no base image cache, skip this flow");
                            trigger.next();
                            return;
                        }

                        GetMd5Cmd cmd = new GetMd5Cmd();
                        GetMd5TO to = new GetMd5TO();
                        to.resourceUuid = "backing-file";
                        to.path = context.baseImageCachePath;
                        cmd.md5s = list(to);
                        cmd.volumeUuid = struct.getVolume().getUuid();
                        cmd.stage = PrimaryStorageConstant.MIGRATE_VOLUME_BACKING_FILE_GET_MD5_STAGE;

                        httpCall(GET_MD5_PATH, struct.getSrcHostUuid(), cmd, false, GetMd5Rsp.class, new ReturnValueCompletion<GetMd5Rsp>(trigger) {
                            @Override
                            public void success(GetMd5Rsp rsp) {
                                context.baseImageCacheMd5 = rsp.md5s.get(0).md5;
                                trigger.next();
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                trigger.fail(errorCode);
                            }
                        });
                    }
                });

                flows.add(new Flow() {
                    String __name__ = "migrate-base-image-cache";

                    boolean s = false;

                    private void migrate(final FlowTrigger trigger) {
                        // sync here for migrating multiple volumes having the same backing file
                        thdf.chainSubmit(new ChainTask(trigger) {
                            @Override
                            public String getSyncSignature() {
                                return String.format("migrate-base-image-cache-%s-to-host-%s", context.baseImageCachePath, struct.getDestHostUuid());
                            }

                            @Override
                            public void run(final SyncTaskChain chain) {
                                final CopyBitsFromRemoteCmd cmd = new CopyBitsFromRemoteCmd();
                                cmd.dstIp = destIp;
                                cmd.dstUsername = username;
                                cmd.dstPassword = password;
                                cmd.dstPort = port;
                                cmd.paths = list(context.baseImageCachePath);
                                cmd.volumeUuid = context.rootVolumeUuid;
                                cmd.stage = PrimaryStorageConstant.MIGRATE_VOLUME_BACKING_FILE_COPY_STAGE;

                                httpCall(LocalStorageKvmMigrateVmFlow.COPY_TO_REMOTE_BITS_PATH, struct.getSrcHostUuid(), cmd, false,
                                        AgentResponse.class, new ReturnValueCompletion<AgentResponse>(trigger, chain) {
                                            @Override
                                            public void success(AgentResponse rsp) {
                                                s = true;
                                                trigger.next();
                                                chain.next();
                                            }

                                            @Override
                                            public void fail(ErrorCode errorCode) {
                                                trigger.fail(errorCode);
                                                chain.next();
                                            }
                                        });
                            }

                            @Override
                            public String getName() {
                                return getSyncSignature();
                            }
                        });
                    }

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        if (context.baseImageCachePath == null) {
                            logger.debug("no base image cache, skip this flow");
                            trigger.next();
                            return;
                        }

                        checkIfExistOnDst(new ReturnValueCompletion<Boolean>(trigger) {
                            @Override
                            public void success(Boolean existing) {
                                if (existing) {
                                    // DO NOT set success = true here, otherwise the rollback
                                    // will delete the backing file which belongs to others on the dst host
                                    logger.debug(String.format("found %s on the dst host[uuid:%s], don't copy it",
                                            context.baseImageCachePath, struct.getDestHostUuid()));
                                    trigger.next();
                                } else {
                                    migrate(trigger);
                                }

                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                trigger.fail(errorCode);
                            }
                        });
                    }

                    private void checkIfExistOnDst(final ReturnValueCompletion<Boolean> completion) {
                        CheckBitsCmd cmd = new CheckBitsCmd();
                        cmd.path = context.baseImageCachePath;
                        cmd.username = username;

                        httpCall(CHECK_BITS_PATH, struct.getDestHostUuid(), cmd, CheckBitsRsp.class, new ReturnValueCompletion<CheckBitsRsp>(completion) {
                            @Override
                            public void success(CheckBitsRsp rsp) {
                                completion.success(rsp.existing);
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                completion.fail(errorCode);
                            }
                        });
                    }

                    @Override
                    public void rollback(FlowRollback trigger, Map data) {
                        if (s) {
                            deleteBits(context.baseImageCachePath, struct.getDestHostUuid(), new Completion(null) {
                                @Override
                                public void success() {
                                    // ignore
                                }

                                @Override
                                public void fail(ErrorCode errorCode) {
                                    //TODO add GC
                                    logger.warn(String.format("failed to delete %s on the host[uuid:%s], %s",
                                            struct.getDestHostUuid(), context.baseImageCachePath, errorCode));
                                }
                            });
                        }

                        trigger.rollback();
                    }
                });

                flows.add(new NoRollbackFlow() {
                    String __name__ = "check-md5-of-base-image-cache-on-dst-host";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        if (context.baseImageCachePath == null) {
                            logger.debug("no base image cache, skip this flow");
                            trigger.next();
                            return;
                        }

                        Md5TO to = new Md5TO();
                        to.resourceUuid = "backing-file";
                        to.path = context.baseImageCachePath;
                        to.md5 = context.baseImageCacheMd5;

                        CheckMd5sumCmd cmd = new CheckMd5sumCmd();
                        cmd.md5s = list(to);
                        cmd.volumeUuid = struct.getVolume().getUuid();
                        cmd.stage = PrimaryStorageConstant.MIGRATE_VOLUME_BACKING_FILE_CHECK_MD5_STAGE;

                        httpCall(CHECK_MD5_PATH, struct.getDestHostUuid(), cmd, false, AgentResponse.class, new ReturnValueCompletion<AgentResponse>(trigger) {
                            @Override
                            public void success(AgentResponse returnValue) {
                                trigger.next();
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                trigger.fail(errorCode);
                            }
                        });
                    }
                });

                flows.add(new Flow() {
                    String __name__ = "build-base-image-cache-record";

                    boolean s;
                    ImageCacheVO vo;
                    ImageCacheShadowVO shadow;

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        if (context.baseImageCachePath == null) {
                            logger.debug("no base image cache, skip this flow");
                            trigger.next();
                            return;
                        }

                        LocalStorageUtils.InstallPath path = new LocalStorageUtils.InstallPath();
                        path.installPath = context.baseImageCachePath;
                        path.hostUuid = struct.getDestHostUuid();
                        String fullPath = path.makeFullPath();

                        new SQLBatch(){
                            @Override
                            protected void scripts() {
                                if (!q(ImageCacheVO.class).eq(ImageCacheVO_.installUrl, fullPath).isExists()) {
                                    s = true;
                                    vo = new ImageCacheVO();
                                    vo.setState(ImageCacheState.ready);
                                    vo.setMediaType(ImageMediaType.RootVolumeTemplate);
                                    vo.setImageUuid(struct.getVolume().getRootImageUuid());
                                    vo.setPrimaryStorageUuid(self.getUuid());
                                    vo.setSize(context.baseImageCacheSize);
                                    vo.setMd5sum(context.baseImageCacheMd5);
                                    vo.setInstallUrl(fullPath);
                                    persist(vo);
                                    shadow = q(ImageCacheShadowVO.class).eq(ImageCacheShadowVO_.installUrl, fullPath).find();
                                    Optional.ofNullable(shadow).ifPresent(this::remove);
                                }
                            }
                        }.execute();

                        trigger.next();
                    }

                    @Override
                    public void rollback(FlowRollback trigger, Map data) {
                        if (s) {
                            new SQLBatch() {
                                @Override
                                protected void scripts() {
                                    Optional.ofNullable(vo).ifPresent(this::remove);
                                    Optional.ofNullable(shadow).ifPresent(it -> {it.setId(0); persist(it);});
                                }
                            }.execute();
                        }

                        trigger.rollback();
                    }
                });
            }
        }

        flows.add(new NoRollbackFlow() {
            String __name__ = "get-md5-on-src-host";

            @Override
            public void run(final FlowTrigger trigger, Map data) {
                GetMd5Cmd cmd = new GetMd5Cmd();
                cmd.sendCommandUrl = restf.getSendCommandUrl();
                cmd.volumeUuid = struct.getVolume().getUuid();
                if (context.hasbackingfile) {
                    cmd.stage = PrimaryStorageConstant.MIGRATE_VOLUME_AFTER_BACKING_FILE_GET_MD5_STAGE;
                } else {
                    cmd.stage = PrimaryStorageConstant.MIGRATE_VOLUME_GET_MD5_STAGE;
                }
                cmd.md5s = CollectionUtils.transformToList(struct.getInfos(), new Function<GetMd5TO, ResourceInfo>() {
                    @Override
                    public GetMd5TO call(ResourceInfo arg) {
                        GetMd5TO to = new GetMd5TO();
                        to.path = arg.getPath();
                        to.resourceUuid = arg.getResourceRef().getResourceUuid();
                        return to;
                    }
                });

                httpCall(GET_MD5_PATH, struct.getSrcHostUuid(), cmd, false, GetMd5Rsp.class, new ReturnValueCompletion<GetMd5Rsp>(trigger) {
                    @Override
                    public void success(GetMd5Rsp rsp) {
                        context.getMd5Rsp = rsp;
                        trigger.next();
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        trigger.fail(errorCode);
                    }
                });
            }
        });

        flows.add(new Flow() {
            String __name__ = "migrate-bits-to-dst-host";

            List<String> migrated;

            @Override
            public void run(final FlowTrigger trigger, Map data) {
                final CopyBitsFromRemoteCmd cmd = new CopyBitsFromRemoteCmd();
                cmd.dstIp = destIp;
                cmd.dstUsername = username;
                cmd.dstPassword = password;
                cmd.dstPort = port;
                cmd.sendCommandUrl = restf.getSendCommandUrl();
                if (context.hasbackingfile) {
                    cmd.stage = PrimaryStorageConstant.MIGRATE_VOLUME_AFTER_BACKING_FILE_COPY_STAGE;
                } else {
                    cmd.stage = PrimaryStorageConstant.MIGRATE_VOLUME_COPY_STAGE;
                }
                cmd.paths = CollectionUtils.transformToList(struct.getInfos(), ResourceInfo::getPath);
                cmd.volumeUuid = struct.getInfos().get(0).getResourceRef().getResourceUuid();

                httpCall(LocalStorageKvmMigrateVmFlow.COPY_TO_REMOTE_BITS_PATH, struct.getSrcHostUuid(), cmd, false,
                        AgentResponse.class, new ReturnValueCompletion<AgentResponse>(trigger) {
                            @Override
                            public void success(AgentResponse rsp) {
                                migrated = cmd.paths;
                                trigger.next();
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                trigger.fail(errorCode);
                            }
                        });
            }

            @Override
            public void rollback(FlowRollback trigger, Map data) {
                if (migrated != null) {
                    new Runnable() {
                        @Override
                        @AsyncThread
                        public void run() {
                            doDelete(migrated.iterator());
                        }

                        private void doDelete(final Iterator<String> it) {
                            if (!it.hasNext()) {
                                return;
                            }

                            final String path = it.next();
                            deleteBits(path, struct.getDestHostUuid(), new Completion(trigger) {
                                @Override
                                public void success() {
                                    doDelete(it);
                                }

                                @Override
                                public void fail(ErrorCode errorCode) {
                                    //TODO add GC
                                    logger.warn(String.format("failed to delete %s on the host[uuid:%s], %s",
                                            path, struct.getDestHostUuid(), errorCode));
                                    doDelete(it);
                                }
                            });
                        }

                    }.run();
                }

                trigger.rollback();
            }
        });

        flows.add(new NoRollbackFlow() {
            String __name__ = "check-md5-on-dst";

            @Override
            public void run(final FlowTrigger trigger, Map data) {
                CheckMd5sumCmd cmd = new CheckMd5sumCmd();
                cmd.sendCommandUrl = restf.getSendCommandUrl();
                cmd.md5s = context.getMd5Rsp.md5s;
                cmd.volumeUuid = struct.getVolume().getUuid();
                if (context.hasbackingfile) {
                    cmd.stage = PrimaryStorageConstant.MIGRATE_VOLUME_AFTER_BACKING_FILE_CHECK_MD5_STAGE;
                } else {
                    cmd.stage = PrimaryStorageConstant.MIGRATE_VOLUME_CHECK_MD5_STAGE;
                }
                httpCall(CHECK_MD5_PATH, struct.getDestHostUuid(), cmd, false, AgentResponse.class, new ReturnValueCompletion<AgentResponse>(trigger) {
                    @Override
                    public void success(AgentResponse rsp) {
                        trigger.next();
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        trigger.fail(errorCode);
                    }
                });
            }
        });

        return flows;
    }

    @Override
    public void detachHook(String clusterUuid, final Completion completion) {
        SimpleQuery<HostVO> q = dbf.createQuery(HostVO.class);
        q.select(HostVO_.uuid);
        q.add(HostVO_.clusterUuid, Op.EQ, clusterUuid);
        final List<String> hostUuids = q.listValue();

        if (hostUuids.isEmpty()) {
            completion.success();
            return;
        }

        SimpleQuery<LocalStorageHostRefVO> refq = dbf.createQuery(LocalStorageHostRefVO.class);
        refq.add(LocalStorageHostRefVO_.primaryStorageUuid, Op.EQ, self.getUuid());
        refq.add(LocalStorageHostRefVO_.hostUuid, Op.IN, hostUuids);
        List<LocalStorageHostRefVO> refs = refq.list();
        if (!refs.isEmpty()) {
            dbf.removeCollection(refs, LocalStorageHostRefVO.class);

            long total = 0;
            long avail = 0;
            long pt = 0;
            long pa = 0;
            long su = 0;
            for (LocalStorageHostRefVO ref : refs) {
                total += ref.getTotalCapacity();
                avail += ref.getAvailableCapacity();
                pt += ref.getTotalPhysicalCapacity();
                pa += ref.getAvailablePhysicalCapacity();
                su += ref.getSystemUsedCapacity();
            }

            // after detaching, total capacity on those hosts should be deducted
            // from both total and available capacity of the primary storage
            decreaseCapacity(total, avail, pt, pa, su);
        }
        completion.success();
    }

    @Override
    public void attachHook(String clusterUuid, final Completion completion) {
        // get all hosts of cluster
        SimpleQuery<HostVO> q = dbf.createQuery(HostVO.class);
        q.select(HostVO_.uuid, HostVO_.status);
        q.add(HostVO_.clusterUuid, Op.EQ, clusterUuid);
        final List<Tuple> ts = q.listTuple();

        if (ts.isEmpty()) {
            completion.success();
            return;
        }

        List<String> connectedHostUuids = ts.stream()
                .filter(it -> it.get(1, HostStatus.class) == HostStatus.Connected)
                .map(it -> it.get(0, String.class))
                .collect(Collectors.toList());
        List<String> disconnectedHostUuids = ts.stream()
                .filter(it -> it.get(1, HostStatus.class) != HostStatus.Connected)
                .map(it -> it.get(0, String.class))
                .collect(Collectors.toList());

        // make init msg for each host
        initHosts(connectedHostUuids, completion);
        initHosts(disconnectedHostUuids, true, new NopeCompletion());
    }

    private void initHosts(List<String> hostUuids, Completion completion){
        initHosts(hostUuids, false, completion);
    }

    private void initHosts(List<String> hostUuids, boolean noCheckStatus, Completion completion){
        if (hostUuids.isEmpty()) {
            completion.success();
            return;
        }

        List<KVMHostAsyncHttpCallMsg> msgs = CollectionUtils.transformToList(hostUuids,
                new Function<KVMHostAsyncHttpCallMsg, String>() {
                    @Override
                    public KVMHostAsyncHttpCallMsg call(String arg) {
                        InitCmd cmd = new InitCmd();
                        cmd.uuid = self.getUuid();
                        cmd.path = self.getUrl();
                        cmd.hostUuid = arg;
                        cmd.initFilePath = makeInitializedFilePath();

                        KVMHostAsyncHttpCallMsg msg = new KVMHostAsyncHttpCallMsg();
                        msg.setCommand(cmd);
                        msg.setPath(INIT_PATH);
                        msg.setHostUuid(arg);
                        msg.setNoStatusCheck(noCheckStatus);
                        bus.makeTargetServiceIdByResourceUuid(msg, HostConstant.SERVICE_ID, arg);
                        return msg;
                    }
                });

        bus.send(msgs, new CloudBusListCallBack(completion) {
            @Override
            public void run(List<MessageReply> replies) {
                long total = 0;
                long avail = 0;
                long systemUsed = 0;
                List<LocalStorageHostRefVO> refs = new ArrayList<>();

                for (MessageReply reply : replies) {
                    String hostUuid = hostUuids.get(replies.indexOf(reply));
                    if (!reply.isSuccess()) {
                        logger.warn(String.format("cannot get the physical capacity of local storage on the host[uuid:%s], %s",
                                hostUuid, reply.getError()));
                        continue;
                    }

                    KVMHostAsyncHttpCallReply r = reply.castReply();
                    InitRsp rsp = r.toResponse(InitRsp.class);
                    if (!rsp.isSuccess()) {
                        logger.warn(String.format("cannot get the physical capacity of local storage on the host[uuid:%s], %s",
                                hostUuid, rsp.getError()));
                        continue;
                    }
                    {
                        SimpleQuery<LocalStorageHostRefVO> sq = dbf.createQuery(LocalStorageHostRefVO.class);
                        sq.add(LocalStorageHostRefVO_.primaryStorageUuid, Op.EQ, self.getUuid());
                        sq.add(LocalStorageHostRefVO_.hostUuid, Op.EQ, hostUuid);
                        if (sq.isExists()) {
                            logger.debug(String.format("host[uuid :%s] is already in the local primary storage[uuid: %s]",
                                    hostUuid, self.getUuid()));
                            continue;
                        }
                    }

                    total += rsp.getTotalCapacity();
                    avail += rsp.getAvailableCapacity();
                    long systemUsedCapacity = rsp.getTotalCapacity() - rsp.getAvailableCapacity() - rsp.getLocalStorageUsedCapacity();
                    systemUsed += systemUsedCapacity;

                    LocalStorageHostRefVO ref = new LocalStorageHostRefVO();
                    ref.setPrimaryStorageUuid(self.getUuid());
                    ref.setHostUuid(hostUuid);
                    ref.setAvailablePhysicalCapacity(rsp.getAvailableCapacity());
                    ref.setAvailableCapacity(rsp.getAvailableCapacity());
                    ref.setTotalCapacity(rsp.getTotalCapacity());
                    ref.setTotalPhysicalCapacity(rsp.getTotalCapacity());
                    ref.setSystemUsedCapacity(systemUsedCapacity);
                    refs.add(ref);
                }

                dbf.persistCollection(refs);
                increaseCapacity(total, avail, total, avail, systemUsed);
                completion.success();
            }
        });
    }

    @Override
    void handle(CreateImageCacheFromVolumeOnPrimaryStorageMsg msg, ReturnValueCompletion<CreateImageCacheFromVolumeOnPrimaryStorageReply> completion) {
        CreateImageCacheFromVolumeOnPrimaryStorageReply reply = new CreateImageCacheFromVolumeOnPrimaryStorageReply();
        final LocalStorageResourceRefVO ref = Q.New(LocalStorageResourceRefVO.class)
                .eq(LocalStorageResourceRefVO_.resourceUuid, msg.getVolumeInventory().getUuid())
                .find();

        ImageCache cache = new ImageCache();
        cache.primaryStorageInstallPath = makeCachedImageInstallUrl(msg.getImageInventory());
        cache.hostUuid = ref.getHostUuid();
        cache.image = msg.getImageInventory();
        cache.volumeResourceInstallPath = msg.getVolumeInventory().getInstallPath();
        cache.download(new ReturnValueCompletion<ImageCacheInventory>(completion) {
            @Override
            public void success(ImageCacheInventory cache) {
                reply.setLocateHostUuid(ref.getHostUuid());
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(CreateImageCacheFromVolumeSnapshotOnPrimaryStorageMsg msg, ReturnValueCompletion<CreateImageCacheFromVolumeSnapshotOnPrimaryStorageReply> completion) {
        CreateImageCacheFromVolumeSnapshotOnPrimaryStorageReply reply = new CreateImageCacheFromVolumeSnapshotOnPrimaryStorageReply();
        final LocalStorageResourceRefVO ref = Q.New(LocalStorageResourceRefVO.class)
                .eq(LocalStorageResourceRefVO_.resourceUuid, msg.getVolumeSnapshot().getUuid())
                .find();

        boolean incremental = msg.hasSystemTag(VolumeSystemTags.FAST_CREATE.getTagFormat());
        if (incremental && PrimaryStorageGlobalProperty.USE_SNAPSHOT_AS_INCREMENTAL_CACHE) {
            ImageCacheVO cache = createTemporaryImageCacheFromVolumeSnapshot(msg.getImageInventory(), msg.getVolumeSnapshot());
            LocalStorageUtils.InstallPath path = new LocalStorageUtils.InstallPath();
            path.volumeSnapshotUuid = msg.getVolumeSnapshot().getUuid();
            path.hostUuid = ref.getHostUuid();
            cache.setInstallUrl(path.makeFullPath());
            dbf.persist(cache);
            reply.setLocateHostUuid(ref.getHostUuid());
            reply.setInventory(cache.toInventory());
            reply.setIncremental(true);
            completion.success(reply);
            return;
        }

        ImageCache cache = new ImageCache();
        cache.primaryStorageInstallPath = makeCachedImageInstallUrl(msg.getImageInventory());
        cache.hostUuid = ref.getHostUuid();
        cache.image = msg.getImageInventory();
        cache.volumeResourceInstallPath = msg.getVolumeSnapshot().getPrimaryStorageInstallPath();
        cache.incremental =incremental;
        cache.download(new ReturnValueCompletion<ImageCacheInventory>(completion) {
            @Override
            public void success(ImageCacheInventory inv) {
                reply.setLocateHostUuid(ref.getHostUuid());
                reply.setInventory(inv);
                reply.setIncremental(cache.incremental);
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(CreateTemplateFromVolumeOnPrimaryStorageMsg msg, ReturnValueCompletion<CreateTemplateFromVolumeOnPrimaryStorageReply> completion) {
        final LocalStorageResourceRefVO ref = Q.New(LocalStorageResourceRefVO.class)
                .eq(LocalStorageResourceRefVO_.resourceUuid, msg.getVolumeInventory().getUuid())
                .find();
        final CreateTemplateFromVolumeOnPrimaryStorageReply reply = new CreateTemplateFromVolumeOnPrimaryStorageReply();

        final TaskProgressRange parentStage = getTaskStage();
        final TaskProgressRange PREPARATION_STAGE = new TaskProgressRange(0, 10);
        final TaskProgressRange CREATE_TEMPORARY_TEMPLATE_STAGE = new TaskProgressRange(10, 30);
        final TaskProgressRange TEMPLATE_UPLOAD_STAGE = new TaskProgressRange(30, 90);
        final TaskProgressRange DELETE_TEMPORARY_TEMPLATE_STAGE = new TaskProgressRange(90, 99);

        FlowChain chain = FlowChainBuilder.newShareFlowChain();
        chain.setName(String.format("create-image-%s-from-volume-%s", msg.getImageInventory().getUuid(), msg.getVolumeInventory().getUuid()));
        chain.then(new ShareFlow() {
            String temporaryTemplatePath = makeTemplateFromVolumeInWorkspacePath(msg.getImageInventory().getUuid());
            String backupStorageInstallPath;

            @Override
            public void setup() {
                flow(new Flow() {
                    String __name__ = "reserve-capacity-on-the-host-for-template";

                    long requiredSize = ratioMgr.calculateByRatio(self.getUuid(), msg.getVolumeInventory().getSize());
                    boolean success = false;

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        TaskProgressRange stage = markTaskStage(parentStage, PREPARATION_STAGE);
                        reserveCapacityOnHost(ref.getHostUuid(), requiredSize, ref.getPrimaryStorageUuid());
                        success = true;
                        reportProgress(stage.getEnd().toString());
                        trigger.next();
                    }

                    @Override
                    public void rollback(FlowRollback trigger, Map data) {
                        if (success) {
                            returnStorageCapacityToHost(ref.getHostUuid(), requiredSize);
                        }
                        trigger.rollback();
                    }
                });

                flow(new Flow() {
                    String __name__ = "create-temporary-template";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        TaskProgressRange stage = markTaskStage(parentStage, CREATE_TEMPORARY_TEMPLATE_STAGE);

                        CreateTemplateFromVolumeCmd cmd = new CreateTemplateFromVolumeCmd();
                        cmd.setInstallPath(temporaryTemplatePath);
                        cmd.setVolumePath(msg.getVolumeInventory().getInstallPath());

                        httpCall(CREATE_TEMPLATE_FROM_VOLUME, ref.getHostUuid(), cmd, false,
                                CreateTemplateFromVolumeRsp.class,
                                new ReturnValueCompletion<CreateTemplateFromVolumeRsp>(trigger) {
                                    @Override
                                    public void success(CreateTemplateFromVolumeRsp rsp) {
                                        reply.setActualSize(rsp.getActualSize());
                                        reportProgress(stage.getEnd().toString());
                                        trigger.next();
                                    }

                                    @Override
                                    public void fail(ErrorCode errorCode) {
                                        trigger.fail(errorCode);
                                    }
                                });
                    }

                    @Override
                    public void rollback(final FlowRollback trigger, Map data) {
                        deleteBits(temporaryTemplatePath, ref.getHostUuid(), new Completion(trigger) {
                            @Override
                            public void success() {
                                trigger.rollback();
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                logger.warn(String.format("failed to delete %s on primary storage[uuid: %s], %s; continue to rollback", temporaryTemplatePath, self.getUuid(), errorCode));
                                trigger.rollback();
                            }
                        });
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "upload-template-to-backup-storage";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        TaskProgressRange stage = markTaskStage(parentStage, TEMPLATE_UPLOAD_STAGE);

                        BackupStorageAskInstallPathMsg bmsg = new BackupStorageAskInstallPathMsg();
                        bmsg.setBackupStorageUuid(msg.getBackupStorageUuid());
                        bmsg.setImageMediaType(msg.getImageInventory().getMediaType());
                        bmsg.setImageUuid(msg.getImageInventory().getUuid());
                        bus.makeTargetServiceIdByResourceUuid(bmsg, BackupStorageConstant.SERVICE_ID, msg.getBackupStorageUuid());
                        MessageReply br = bus.call(bmsg);
                        if (!br.isSuccess()) {
                            trigger.fail(br.getError());
                            return;
                        }

                        backupStorageInstallPath = ((BackupStorageAskInstallPathReply) br).getInstallPath();
                        BackupStorageVO bsvo = dbf.findByUuid(msg.getBackupStorageUuid(), BackupStorageVO.class);
                        LocalStorageBackupStorageMediator m = localStorageFactory.getBackupStorageMediator(KVMConstant.KVM_HYPERVISOR_TYPE, bsvo.getType());
                        m.uploadBits(msg.getImageInventory().getUuid(), getSelfInventory(), BackupStorageInventory.valueOf(bsvo), backupStorageInstallPath, temporaryTemplatePath, ref.getHostUuid(), new ReturnValueCompletion<String>(trigger) {
                            @Override
                            public void success(String installPath) {
                                backupStorageInstallPath = installPath;
                                reportProgress(stage.getEnd().toString());
                                trigger.next();
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                trigger.fail(errorCode);
                            }
                        });
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "delete-temporary-template-on-primary-storage";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        TaskProgressRange stage = markTaskStage(parentStage, DELETE_TEMPORARY_TEMPLATE_STAGE);
                        deleteBits(temporaryTemplatePath, ref.getHostUuid(), new Completion(trigger) {
                            @Override
                            public void success() {
                                reportProgress(stage.getEnd().toString());
                                trigger.next();
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                //TODO: add GC
                                logger.warn(String.format("failed to delete %s on local primary storage[uuid: %s], %s; need a cleanup", temporaryTemplatePath, self.getUuid(), errorCode));
                                trigger.next();
                            }
                        });
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "return-capacity-of-temporary-template-to-host";

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        returnStorageCapacityToHost(ref.getHostUuid(), msg.getVolumeInventory().getSize());
                        trigger.next();
                    }
                });

                done(new FlowDoneHandler(msg) {
                    @Override
                    public void handle(Map data) {
                        reply.setFormat(msg.getVolumeInventory().getFormat());
                        reply.setTemplateBackupStorageInstallPath(backupStorageInstallPath);
                        completion.success(reply);
                    }
                });

                error(new FlowErrorHandler(msg) {
                    @Override
                    public void handle(ErrorCode errCode, Map data) {
                        completion.fail(errCode);
                    }
                });
            }
        }).start();
    }

    @Override
    public void handle(AskInstallPathForNewSnapshotMsg msg, ReturnValueCompletion<AskInstallPathForNewSnapshotReply> completion) {
        AskInstallPathForNewSnapshotReply reply = new AskInstallPathForNewSnapshotReply();
        reply.setSnapshotInstallPath(makeSnapshotInstallPath(msg.getVolumeInventory(), msg.getSnapshotUuid()));
        completion.success(reply);
    }

    @Override
    void handle(DownloadBitsFromKVMHostToPrimaryStorageMsg msg, ReturnValueCompletion<DownloadBitsFromKVMHostToPrimaryStorageReply> completion) {
        DownloadBitsFromKVMHostToPrimaryStorageReply reply = new DownloadBitsFromKVMHostToPrimaryStorageReply();

        GetKVMHostDownloadCredentialMsg gmsg = new GetKVMHostDownloadCredentialMsg();
        gmsg.setHostUuid(msg.getSrcHostUuid());

        if (PrimaryStorageSystemTags.PRIMARY_STORAGE_GATEWAY.hasTag(self.getUuid())) {
            gmsg.setDataNetworkCidr(PrimaryStorageSystemTags.PRIMARY_STORAGE_GATEWAY.getTokenByResourceUuid(self.getUuid(), PrimaryStorageSystemTags.PRIMARY_STORAGE_GATEWAY_TOKEN));
        }

        bus.makeTargetServiceIdByResourceUuid(gmsg, HostConstant.SERVICE_ID, msg.getSrcHostUuid());
        bus.send(gmsg, new CloudBusCallBack(completion) {
            @Override
            public void run(MessageReply rly) {
                if (!rly.isSuccess()) {
                    completion.fail(rly.getError());
                    return;
                }

                GetKVMHostDownloadCredentialReply grly = rly.castReply();
                DownloadBitsFromKVMHostCmd cmd = new DownloadBitsFromKVMHostCmd();
                cmd.hostname = grly.getHostname();
                cmd.username = grly.getUsername();
                cmd.sshKey = grly.getSshKey();
                cmd.sshPort = grly.getSshPort();
                cmd.backupStorageInstallPath = msg.getHostInstallPath();
                cmd.primaryStorageInstallPath = msg.getPrimaryStorageInstallPath();
                cmd.bandWidth = msg.getBandWidth();
                cmd.identificationCode = msg.getLongJobUuid() + msg.getPrimaryStorageInstallPath();
                httpCall(DOWNLOAD_BITS_FROM_KVM_HOST_PATH, msg.getDestHostUuid(), cmd, true, DownloadBitsFromKVMHostRsp.class, new ReturnValueCompletion<DownloadBitsFromKVMHostRsp>(completion) {
                    @Override
                    public void success(DownloadBitsFromKVMHostRsp rsp) {
                        reply.setFormat(rsp.format);
                        completion.success(reply);
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        completion.fail(errorCode);
                    }
                });
            }
        });

    }

    @Override
    void handle(CancelDownloadBitsFromKVMHostToPrimaryStorageMsg msg, ReturnValueCompletion<CancelDownloadBitsFromKVMHostToPrimaryStorageReply> completion) {
        CancelDownloadBitsFromKVMHostCmd cmd = new CancelDownloadBitsFromKVMHostCmd();
        cmd.primaryStorageInstallPath = msg.getPrimaryStorageInstallPath();
        httpCall(CANCEL_DOWNLOAD_BITS_FROM_KVM_HOST_PATH, msg.getDestHostUuid(), cmd, true, AgentResponse.class, new ReturnValueCompletion<AgentResponse>(completion) {
            @Override
            public void success(AgentResponse rsp) {
                completion.success(new CancelDownloadBitsFromKVMHostToPrimaryStorageReply());
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(ChangeVolumeTypeOnPrimaryStorageMsg msg, ReturnValueCompletion<ChangeVolumeTypeOnPrimaryStorageReply> completion) {
        ChangeVolumeTypeOnPrimaryStorageReply reply = new ChangeVolumeTypeOnPrimaryStorageReply();

        String originType = msg.getVolume().getType();
        LinkVolumeNewDirCmd cmd = new LinkVolumeNewDirCmd();
        cmd.srcDir = makeVolumeInstallDir(msg.getVolume());
        msg.getVolume().setType(msg.getTargetType().toString());
        cmd.dstDir = makeVolumeInstallDir(msg.getVolume());
        msg.getVolume().setType(originType);
        cmd.volumeUuid = msg.getVolume().getUuid();

        if (!msg.getVolume().getInstallPath().startsWith(cmd.srcDir)) {
             completion.fail(operr("why volume[uuid:%s, installPath:%s] not in directory %s",
                     cmd.volumeUuid, msg.getVolume().getInstallPath(), cmd.srcDir));
             return;
        }

        String hostUuid = getHostUuidByResourceUuid(cmd.volumeUuid, VolumeVO.class.toString());

        httpCall(HARD_LINK_VOLUME, hostUuid, cmd, LinkVolumeNewDirRsp.class, new ReturnValueCompletion<LinkVolumeNewDirRsp>(completion) {
            @Override
            public void success(LinkVolumeNewDirRsp rsp) {
                VolumeInventory vol = msg.getVolume();
                String newPath = vol.getInstallPath().replace(cmd.srcDir, cmd.dstDir);
                vol.setInstallPath(newPath);
                reply.setVolume(vol);

                for (VolumeSnapshotInventory snapshot : msg.getSnapshots()) {
                    newPath = snapshot.getPrimaryStorageInstallPath().replace(cmd.srcDir, cmd.dstDir);
                    snapshot.setPrimaryStorageInstallPath(newPath);
                }
                reply.getSnapshots().addAll(msg.getSnapshots());
                reply.setInstallPathToGc(cmd.srcDir);
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(UnlinkBitsOnPrimaryStorageMsg msg, ReturnValueCompletion<UnlinkBitsOnPrimaryStorageReply> completion) {
        String hostUuid = getHostUuidByResourceUuid(msg.getResourceUuid(), msg.getResourceType());
        UnlinkBitsCmd cmd = new UnlinkBitsCmd();
        cmd.installPath = msg.getInstallPath();
        httpCall(UNLINK_BITS_PATH, hostUuid, cmd, UnlinkBitsRsp.class, new ReturnValueCompletion<UnlinkBitsRsp>(completion) {
            @Override
            public void success(UnlinkBitsRsp rsp) {
                completion.success(new UnlinkBitsOnPrimaryStorageReply());
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void checkHostAttachedPSMountPath(String hostUuid, Completion completion) {
        CheckInitializedFileCmd cmd = new CheckInitializedFileCmd();
        cmd.uuid = self.getUuid();
        cmd.filePath = makeInitializedFilePath();
        cmd.storagePath = self.getUrl();

        // check flag doesn't care about host status
        httpCall(CHECK_INITIALIZED_FILE, hostUuid, cmd, true, CheckInitializedFileRsp.class, new ReturnValueCompletion<CheckInitializedFileRsp>(completion) {
            @Override
            public void success(CheckInitializedFileRsp rsp) {
                if (!rsp.existed) {
                    completion.fail(operr("cannot find flag file [%s] on host [%s], it might not mount correct path", makeInitializedFilePath(), hostUuid));
                } else {
                    completion.success();
                }
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(operr("cannot find flag file [%s] on host [%s], because: %s", makeInitializedFilePath(), hostUuid, errorCode.getCause().getDetails()));
            }
        });
    }

    @Override
    void initializeHostAttachedPSMountPath(String hostUuid, Completion completion) {
        CreateInitializedFileCmd cmd = new CreateInitializedFileCmd();
        cmd.uuid = self.getUuid();
        cmd.filePath = makeInitializedFilePath();
        cmd.storagePath = self.getUrl();

        // create flag doesn't care about host status
        httpCall(CREATE_INITIALIZED_FILE, hostUuid, cmd, true, AgentResponse.class, new ReturnValueCompletion<AgentResponse>(completion) {
            @Override
            public void success(AgentResponse rsp) {
                completion.success();
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(operr("cannot create flag file [%s] on host [%s], because: %s", makeInitializedFilePath(), hostUuid, errorCode.getCause().getDetails()));
            }
        });
    }

    @Override
    void handle(GetDownloadBitsFromKVMHostProgressMsg msg, ReturnValueCompletion<GetDownloadBitsFromKVMHostProgressReply> completion) {
        GetDownloadBitsFromKVMHostProgressReply reply = new GetDownloadBitsFromKVMHostProgressReply();
        GetDownloadBitsFromKVMHostProgressCmd cmd = new GetDownloadBitsFromKVMHostProgressCmd();
        cmd.volumePaths = msg.getVolumePaths();
        httpCall(GET_DOWNLOAD_BITS_FROM_KVM_HOST_PROGRESS_PATH, msg.getHostUuid(), cmd, true, GetDownloadBitsFromKVMHostProgressRsp.class, new ReturnValueCompletion<GetDownloadBitsFromKVMHostProgressRsp>(completion) {
            @Override
            public void success(GetDownloadBitsFromKVMHostProgressRsp rsp) {
                reply.setTotalSize(rsp.totalSize);
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    void handle(GetVolumeSnapshotEncryptedOnPrimaryStorageMsg msg, ReturnValueCompletion<GetVolumeSnapshotEncryptedOnPrimaryStorageReply> completion) {
        GetVolumeSnapshotEncryptedOnPrimaryStorageReply reply = new GetVolumeSnapshotEncryptedOnPrimaryStorageReply();
        GetQcow2HashValueCmd cmd = new GetQcow2HashValueCmd();

        String hostUuid = getHostUuidByResourceUuid(msg.getSnapshotUuid(), VolumeSnapshotVO.class.getSimpleName());
        cmd.setHostUuid(hostUuid);
        cmd.setInstallPath(msg.getPrimaryStorageInstallPath());
        httpCall(GET_QCOW2_HASH_VALUE_PATH, hostUuid, cmd, true, GetQcow2HashValueRsp.class, new ReturnValueCompletion<GetQcow2HashValueRsp>(completion) {

            @Override
            public void success(GetQcow2HashValueRsp returnValue) {
                reply.setSnapshotUuid(msg.getSnapshotUuid());
                reply.setEncrypt(returnValue.getHashValue());
                completion.success(reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    private String makeInitializedFilePath() {
        return String.format("%s/%s-initialized-file", self.getMountPath(), self.getUuid());
    }
}
