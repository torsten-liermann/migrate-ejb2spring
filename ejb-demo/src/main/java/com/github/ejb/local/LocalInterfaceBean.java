package com.github.ejb.local;

import javax.ejb.Local;
import javax.ejb.Stateless;

/**
 * EJB implementing a @Local interface.
 * @Local should be removed, interface remains as regular Java interface.
 */
@Stateless
@Local(LocalInterface.class)
public class LocalInterfaceBean implements LocalInterface {

    @Override
    public String doSomething(String input) {
        return "Processed: " + input;
    }
}
