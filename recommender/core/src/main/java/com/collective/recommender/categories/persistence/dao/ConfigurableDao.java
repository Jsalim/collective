package com.collective.recommender.categories.persistence.dao;

import java.util.Properties;

/**
 * @author Matteo Moci ( matteo (dot) moci (at) gmail (dot) com )
 */
public abstract class ConfigurableDao {

    protected Properties properties;

    public ConfigurableDao(Properties properties) {
        this.properties = properties;
    }

    public Properties getProperties() {
        return properties;
    }
}
