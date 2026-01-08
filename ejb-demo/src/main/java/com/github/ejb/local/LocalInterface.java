package com.github.ejb.local;

import javax.ejb.Local;

/**
 * Local business interface.
 * @Local annotation should be removed during migration.
 */
@Local
public interface LocalInterface {

    String doSomething(String input);
}
