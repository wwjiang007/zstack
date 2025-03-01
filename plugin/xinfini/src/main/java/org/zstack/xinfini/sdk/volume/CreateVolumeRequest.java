package org.zstack.xinfini.sdk.volume;

import org.springframework.http.HttpMethod;
import org.zstack.externalStorage.sdk.Param;
import org.zstack.header.xinfini.XInfiniConstants;
import org.zstack.xinfini.XInfiniApiCategory;
import org.zstack.xinfini.sdk.XInfiniRequest;
import org.zstack.xinfini.sdk.XInfiniRestRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * @ Author : yh.w
 * @ Date   : Created in 17:36 2024/5/27
 */
@XInfiniRestRequest(
    path = "/bs-volumes",
    method = HttpMethod.POST,
    responseClass = CreateVolumeResponse.class,
    category = XInfiniApiCategory.AFA
)
public class CreateVolumeRequest extends XInfiniRequest {
    @Param
    private String name;

    @Param
    private int poolId;

    @Param
    private long sizeMb;

    @Param
    private String creator = XInfiniConstants.DEFAULT_CREATOR;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getPoolId() {
        return poolId;
    }

    public void setPoolId(int poolId) {
        this.poolId = poolId;
    }

    public long getSizeMb() {
        return sizeMb;
    }

    public void setSizeMb(long sizeMb) {
        this.sizeMb = sizeMb;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    private static final HashMap<String, Parameter> parameterMap = new HashMap<>();

    @Override
    public Map<String, Parameter> getParameterMap() {
        return parameterMap;
    }

}
