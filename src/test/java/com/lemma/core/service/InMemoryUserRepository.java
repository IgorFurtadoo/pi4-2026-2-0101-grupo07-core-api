package com.lemma.core.service;

import com.lemma.core.model.User;
import com.lemma.core.repository.EmailAlreadyRegisteredException;
import com.lemma.core.repository.UserRepository;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * UserRepository em memória para os testes, sem banco (técnica do TaskWorkerTest
 * do Blockchain Service). Simula o AUTO_INCREMENT e a UNIQUE do e-mail.
 *
 * NÃO prova: o Hibernate validate, o mapeamento de Instant em DATETIME(3), a
 * restrição UNIQUE real nem a leitura do nome da restrição na exceção do MySQL.
 * Isso é provado no banco real (verificação do passo C2).
 */
public class InMemoryUserRepository extends UserRepository {

    public final List<User> saved = new ArrayList<>();

    /** Quando não nulo, o save lança esta exceção (banco fora, por exemplo). */
    public RuntimeException failOnSave;

    /** Quando verdadeiro, findByEmail não acha ninguém: simula a corrida entre dois cadastros. */
    public boolean hideFromFind;

    public int saveCalls;

    public InMemoryUserRepository() {
        super(null);
    }

    @Override
    public void save(User user) {
        saveCalls++;
        if (failOnSave != null) {
            throw failOnSave;
        }
        boolean duplicate = saved.stream().anyMatch(existing -> existing.getEmail().equals(user.getEmail()));
        if (duplicate) {
            throw new EmailAlreadyRegisteredException();
        }
        setId(user, saved.size() + 1L);
        saved.add(user);
    }

    @Override
    public Optional<User> findById(long id) {
        return saved.stream().filter(user -> user.getId() == id).findFirst();
    }

    @Override
    public Optional<User> findByEmail(String email) {
        if (hideFromFind) {
            return Optional.empty();
        }
        return saved.stream().filter(user -> user.getEmail().equals(email)).findFirst();
    }

    /** O id é gerado pelo banco e a entidade não tem setter; aqui, só no teste, por reflexão. */
    private static void setId(User user, long id) {
        try {
            Field field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
