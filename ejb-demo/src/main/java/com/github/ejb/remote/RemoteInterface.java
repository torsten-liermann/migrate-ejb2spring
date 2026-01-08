package com.github.ejb.remote;

import javax.ejb.Remote;

/**
 * Remote business interface.
 * Note: Remote EJBs cannot be automatically migrated to Spring.
 * They require manual conversion to REST API, gRPC, or similar.
 */
@Remote
public interface RemoteInterface {

    String remoteOperation(String input);
}
