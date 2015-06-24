package com.thoughtworks.go.config.remote;

import com.thoughtworks.go.config.materials.ScmMaterialConfig;
import com.thoughtworks.go.domain.materials.MaterialConfig;
import com.thoughtworks.go.util.StringUtil;

/**
 * @understands that configuration is defined in versioned source code repository at particular revision.
 */
public class RepoConfigOrigin implements ConfigOrigin {

    private ConfigRepoConfig configRepo;
    private String revision;

    public RepoConfigOrigin()
    {
    }
    public RepoConfigOrigin(ConfigRepoConfig configRepo,String revision)
    {
        this.configRepo = configRepo;
        this.revision = revision;
    }

    @Override
    public boolean canEdit() {
        // if there will be implementation to edit repository then this
        // class should participate in providing info on how to do that
        return false;
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    public MaterialConfig getMaterial() {
        if(configRepo == null)
            return  null;
        return configRepo.getMaterialConfig();
    }

    public boolean isFromRevision(String revision) {
        return this.revision.equals(revision);
    }

    public void setConfigRepo(ConfigRepoConfig configRepo) {
        this.configRepo = configRepo;
    }
}
