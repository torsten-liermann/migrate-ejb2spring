package com.github.ejb.entity;

import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.List;

/**
 * EJB Repository using @PersistenceContext.
 * Demonstrates:
 * - @PersistenceContext injection
 * - Transaction attributes on data access methods
 */
@Stateless
public class CustomerRepository {

    @PersistenceContext(unitName = "customerPU")
    private EntityManager entityManager;

    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public Customer save(Customer customer) {
        if (customer.getId() == null) {
            entityManager.persist(customer);
            return customer;
        } else {
            return entityManager.merge(customer);
        }
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public Customer findById(Long id) {
        return entityManager.find(Customer.class, id);
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public List<Customer> findAll() {
        return entityManager.createQuery("SELECT c FROM Customer c", Customer.class)
                .getResultList();
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public void delete(Long id) {
        Customer customer = findById(id);
        if (customer != null) {
            entityManager.remove(customer);
        }
    }
}
