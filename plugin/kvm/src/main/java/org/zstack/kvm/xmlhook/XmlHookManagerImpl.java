package org.zstack.kvm.xmlhook;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.zstack.compute.vm.VmSystemTags;
import org.zstack.core.Platform;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.Q;
import org.zstack.core.thread.ThreadFacade;
import org.zstack.header.AbstractService;
import org.zstack.header.Component;
import org.zstack.header.cluster.ClusterUpdateOSExtensionPoint;
import org.zstack.header.cluster.ClusterVO;
import org.zstack.header.cluster.UpdateClusterOSStruct;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.errorcode.SysErrors;
import org.zstack.header.managementnode.PrepareDbInitialValueExtensionPoint;
import org.zstack.header.message.APIMessage;
import org.zstack.header.message.Message;
import org.zstack.header.vm.VmInstanceBeforeStartExtensionPoint;
import org.zstack.header.vm.VmInstanceVO;
import org.zstack.header.vm.VmInstanceVO_;
import org.zstack.tag.PatternedSystemTag;
import org.zstack.tag.SystemTagUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.logging.CLogger;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static org.zstack.core.Platform.err;

public class XmlHookManagerImpl extends AbstractService implements XmlHookManager, Component,
        PrepareDbInitialValueExtensionPoint, VmInstanceBeforeStartExtensionPoint, ClusterUpdateOSExtensionPoint {
    private static final CLogger logger = Utils.getLogger(XmlHookManagerImpl.class);

    @Autowired
    private CloudBus bus;
    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    protected ThreadFacade thdf;

    private void handleLocalMessage(Message msg) {
        bus.dealWithUnknownMessage(msg);
    }

    @Override
    public void handleMessage(Message msg) {
        if (msg instanceof XmlHookMessage) {
            passThrough((XmlHookMessage) msg);
        } else if (msg instanceof APIMessage) {
            handleApiMessage((APIMessage) msg);
        } else {
            handleLocalMessage(msg);
        }
    }

    private void handleApiMessage(APIMessage msg) {
        if (msg instanceof APICreateVmUserDefinedXmlHookScriptMsg) {
            handle((APICreateVmUserDefinedXmlHookScriptMsg) msg);
        } else {
            bus.dealWithUnknownMessage(msg);
        }
    }

    private void passThrough(XmlHookMessage msg) {
        XmlHookVO vo = dbf.findByUuid(msg.getXmlHookUuid(), XmlHookVO.class);
        if (vo == null) {
            bus.replyErrorByMessageType((Message) msg, err(SysErrors.RESOURCE_NOT_FOUND, "unable to find xmlHook[uuid=%s]", msg.getXmlHookUuid()));
            return;
        }

        XmlHookBase base = new XmlHookBase(vo);
        base.handleMessage((Message) msg);
    }

    private void handle(APICreateVmUserDefinedXmlHookScriptMsg msg) {
        APICreateVmUserDefinedXmlHookScriptEvent event = new APICreateVmUserDefinedXmlHookScriptEvent(msg.getId());
        XmlHookVO vo = new XmlHookVO();
        vo.setUuid(msg.getResourceUuid() == null ? Platform.getUuid() : msg.getResourceUuid());
        vo.setName(msg.getName());
        vo.setDescription(msg.getDescription());
        vo.setHookScript(msg.getHookScript());
        vo.setType(XmlHookType.Customization);
        vo.setCreateDate(new Timestamp(new Date().getTime()));
        dbf.persist(vo);
        event.setInventory(XmlHookInventory.valueOf(vo));
        bus.publish(event);
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    public String getId() {
        return bus.makeLocalServiceId(SERVICE_ID);
    }

    @Override
    public void prepareDbInitialValue() {
    }

    @Override
    public ErrorCode handleSystemTag(String vmUuid, List<String> tags) {
        PatternedSystemTag tag = VmSystemTags.XML_HOOK;
        String token = VmSystemTags.XML_HOOK_TOKEN;

        String xmlhookUuid = SystemTagUtils.findTagValue(tags, tag, token);
        if (StringUtils.isEmpty(xmlhookUuid)) {
            return null;
        }
        XmlHookVmInstanceRefVO refVO = new XmlHookVmInstanceRefVO();
        refVO.setXmlHookUuid(xmlhookUuid);
        refVO.setVmInstanceUuid(vmUuid);
        refVO.setLastOpDate(new Timestamp(new Date().getTime()));
        refVO.setCreateDate(new Timestamp(new Date().getTime()));
        dbf.persist(refVO);
        return null;
    }

    @Override
    public String preUpdateClusterOS(UpdateClusterOSStruct updateClusterOSStruct) {
        if (updateClusterOSStruct.isForce()) {
            return null;
        }
        String updatePackages = updateClusterOSStruct.getUpdatePackages();
        String excludePackages = updateClusterOSStruct.getExcludePackages();
        ClusterVO cluster = updateClusterOSStruct.getCluster();

        boolean qemuOrLibvirtUpdated = updatePackages != null && (updatePackages.contains("qemu-kvm-ev") || updatePackages.contains("qemu-kvm") || updatePackages.contains("qemu") || updatePackages.contains("libvirt"));
        boolean qemuAndLibvirtExcluded = excludePackages != null && (excludePackages.contains("qemu-kvm-ev") || excludePackages.contains("qemu-kvm") || excludePackages.contains("qemu")) && excludePackages.contains("libvirt");
        boolean osUpdatedWithHypervisor = StringUtils.isNotEmpty(updateClusterOSStruct.getReleaseVersion()) && !qemuAndLibvirtExcluded;

        if (qemuOrLibvirtUpdated || osUpdatedWithHypervisor) {
            //Determine whether the VM in the cluster is bound to XML hook
            List<VmInstanceVO> vms = Q.New(VmInstanceVO.class).eq(VmInstanceVO_.clusterUuid, cluster.getUuid()).list();
            if (CollectionUtils.isEmpty(vms)) {
                return null;
            }
            List<VmInstanceVO> vmList = vms.stream()
                    .filter(it -> Q.New(XmlHookVmInstanceRefVO.class)
                            .eq(XmlHookVmInstanceRefVO_.vmInstanceUuid, it.getUuid()).isExists())
                    .collect(Collectors.toList());
            if (CollectionUtils.isNotEmpty(vmList)) {
                return String.format("detect %s vms in cluster[uuid: %s] have been set XML hook, " +
                        "if the upgrade QEMU or libvirt, the original XML hook " +
                        "may be unavailable and the vm service will be affected, " +
                        "please confirm whether to upgrade, " +
                        "and if so, add param [force=true] with the api", vmList.size(), cluster.getUuid());
            }

        }
        return null;
    }

    @Override
    public void beforeUpdateClusterOS(ClusterVO cls) {

    }

    @Override
    public void afterUpdateClusterOS(ClusterVO cls) {

    }
}
