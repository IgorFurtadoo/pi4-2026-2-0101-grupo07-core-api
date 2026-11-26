package com.lemma.core.socket;

import com.lemma.socket.Message;

import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

/**
 * A conexão do Core API com o Blockchain Service no canal de socket (CT27).
 * Corresponde ao Parceiro do exemplo do professor: guarda o socket e os dois
 * streams de objetos, envia e recebe mensagens e fecha a conexão. Só a thread
 * TaskNoticeListener usa, por isso não tem semáforo.
 */
public class Partner {

    /**
     * Filtro de desserialização (CT27): só as mensagens do protocolo e String, com
     * profundidade máxima 5; qualquer outra classe é recusada antes de ser instanciada.
     * Sem maxbytes nem maxrefs: o JDK conta esses limites no stream inteiro (o reset()
     * do servidor não os zera), e a conexão longa cairia depois de alguns avisos.
     */
    public static final ObjectInputFilter INPUT_FILTER =
            ObjectInputFilter.Config.createFilter("maxdepth=5;com.lemma.socket.*;java.lang.String;!*");

    private final Socket connection;
    private final ObjectInputStream receiver;
    private final ObjectOutputStream transmitter;

    /**
     * Abre os streams na ordem do exemplo: o ObjectOutputStream primeiro (com flush do
     * cabeçalho), depois o ObjectInputStream, com o filtro aplicado logo em seguida.
     */
    public Partner(Socket connection) throws IOException {
        this.connection = connection;
        this.transmitter = new ObjectOutputStream(connection.getOutputStream());
        this.transmitter.flush();
        this.receiver = new ObjectInputStream(connection.getInputStream());
        this.receiver.setObjectInputFilter(INPUT_FILTER);
    }

    /** Envia uma mensagem ao servidor. */
    public void send(Message message) throws IOException {
        transmitter.writeObject(message);
        transmitter.flush();
    }

    /** Lê a próxima mensagem. Bloqueia até chegar algo ou a conexão cair. */
    public Message read() throws IOException, ClassNotFoundException {
        return (Message) receiver.readObject();
    }

    /** Fecha a conexão (e os dois streams); erros ao fechar são ignorados. */
    public void close() {
        try {
            connection.close();
        } catch (IOException e) {
            // nada a fazer: a conexão já está indo embora
        }
    }
}
