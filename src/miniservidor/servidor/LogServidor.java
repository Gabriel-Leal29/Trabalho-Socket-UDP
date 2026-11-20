package miniservidor.servidor;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

// Formato: [AAAA-MM-DD HH:MM:SS] [IP:porta] [OPERAÇÃO] [STATUS] detalhes
public class LogServidor {

    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path caminho;
    private final PrintWriter arquivo;
    private final List<Consumer<String>> ouvintes = new CopyOnWriteArrayList<>();

    public LogServidor(Path caminho) throws IOException {
        this.caminho = caminho.toAbsolutePath();
        Files.createDirectories(this.caminho.getParent());
        this.arquivo = new PrintWriter(Files.newBufferedWriter(this.caminho, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND), true);
    }

    public synchronized void registrar(String endereco, String operacao, String status, String detalhes) {
        String linha = String.format("[%s] [%s] [%s] [%s] %s",
                LocalDateTime.now().format(FORMATO_DATA), endereco, operacao, status, detalhes);
        arquivo.println(linha);
        System.out.println(linha);
        for (Consumer<String> ouvinte : ouvintes) {
            ouvinte.accept(linha);
        }
    }

    public void adicionarOuvinte(Consumer<String> ouvinte) {
        ouvintes.add(ouvinte);
    }

    public Path getCaminho() {
        return caminho;
    }
}
