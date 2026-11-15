package com.lemma.core.repository;

import com.lemma.core.model.User;
import org.hibernate.SessionFactory;
import org.hibernate.exception.ConstraintViolationException;

import java.util.Locale;
import java.util.Optional;

/**
 * Acesso à tabela app_user (CT2). Cada método abre e fecha a própria transação
 * (seção 4.2 do desenho). Recebe o e-mail já normalizado pelo UserService.
 * Não loga nada: as mensagens do banco podem trazer o e-mail.
 */
public class UserRepository {

    /** Nome da restrição de unicidade do e-mail no schema.sql. */
    static final String EMAIL_UNIQUE_CONSTRAINT = "uk_app_user_email";

    private final SessionFactory sessionFactory;

    public UserRepository(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    /**
     * Grava o usuário; o id gerado pelo banco fica na própria entidade.
     *
     * @throws EmailAlreadyRegisteredException se outro usuário já tem o mesmo e-mail
     *         (corrida entre dois cadastros). Qualquer outra falha, inclusive a
     *         unicidade do endereço, sobe como está.
     */
    public void save(User user) {
        try {
            sessionFactory.inTransaction(session -> session.persist(user));
        } catch (ConstraintViolationException e) {
            if (isEmailUniqueViolation(e)) {
                throw new EmailAlreadyRegisteredException();
            }
            throw e;
        }
    }

    public Optional<User> findById(long id) {
        return sessionFactory.fromTransaction(session -> Optional.ofNullable(session.find(User.class, id)));
    }

    public Optional<User> findByEmail(String email) {
        return sessionFactory.fromTransaction(session -> session
                .createSelectionQuery("from User where email = :email", User.class)
                .setParameter("email", email)
                .uniqueResultOptional());
    }

    /** O MySQL 8 pode devolver o nome com a tabela na frente: app_user.uk_app_user_email. */
    private static boolean isEmailUniqueViolation(ConstraintViolationException e) {
        String name = e.getConstraintName();
        return name != null && name.toLowerCase(Locale.ROOT).contains(EMAIL_UNIQUE_CONSTRAINT);
    }
}
