package org.zstack.compute.vm;

import org.springframework.beans.factory.annotation.Autowired;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.header.core.workflow.FlowException;
import org.zstack.header.network.l3.UsedIpInventory;
import org.zstack.header.network.l3.UsedIpVO;
import org.zstack.header.vm.*;
import org.zstack.identity.Account;
import org.zstack.utils.Utils;
import org.zstack.utils.logging.CLogger;

import java.util.ArrayList;
import java.util.List;

import static org.zstack.core.Platform.err;


public class TfVmNicFactory extends VmNicFactory {
    private static final CLogger logger = Utils.getLogger(TfVmNicFactory.class);
    private static final VmNicType type = new VmNicType(VmInstanceConstant.TF_VIRTUAL_NIC_TYPE);
    @Autowired
    private CloudBus bus;
    @Autowired
    private DatabaseFacade dbf;

    @Override
    public VmNicType getType() {
        return type;
    }

    public VmNicVO createVmNic(VmNicInventory nic, VmInstanceSpec spec, List<UsedIpInventory> ips) {
        String acntUuid = Account.getAccountUuidOfResource(spec.getVmInventory().getUuid());

        VmNicVO vnic = VmInstanceNicFactory.createVmNic(nic);
        vnic.setType(type.toString());
        vnic.setAccountUuid(acntUuid);
        vnic = persistAndRetryIfMacCollision(vnic);
        if (vnic == null) {
            throw new FlowException(err(VmErrors.ALLOCATE_MAC_ERROR, "unable to find an available mac address after re-try 5 times, too many collisions"));
        }

        List<UsedIpVO> ipVOS = new ArrayList<>();
        for (UsedIpInventory ip : ips) {
            /* update usedIpVo */
            UsedIpVO ipVO = dbf.findByUuid(ip.getUuid(), UsedIpVO.class);
            ipVO.setVmNicUuid(vnic.getUuid());
            ipVOS.add(ipVO);
        }
        dbf.updateCollection(ipVOS);

        vnic = dbf.reload(vnic);
        spec.getDestNics().add(VmNicInventory.valueOf(vnic));
        return vnic;
    }
}
