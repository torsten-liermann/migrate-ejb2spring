package com.github.ejb.remote;

import javax.ejb.Remote;
import javax.ejb.Stateless;

/**
 * EJB with @Remote interface.
 * Migration will add a TODO comment - manual conversion required.
 */
@Stateless
@Remote(RemoteInterface.class)
public class RemoteBean implements RemoteInterface {

    @Override
    public String remoteOperation(String input) {
        return "Remote result: " + input;
    }
}
