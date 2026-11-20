package miniservidor.servidor;

import miniservidor.comum.InfoArquivo;
import miniservidor.comum.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

// Pasta central. Nome duplicado: o servidor renomeia ("foto (1).jpg"), assim nenhum upload apaga
// o arquivo de outra pessoa. O nome fica reservado durante o upload e os bytes ficam em
// .uploads_parciais até o hash ser conferido, então a lista nunca mostra arquivo pela metade.
public class RepositorioArquivos {

    public record Lista(int versao, List<InfoArquivo> arquivos) {
    }

    private static final Set<String> IGNORADOS = Set.of(".ds_store", "thumbs.db", "desktop.ini");

    private final Path pasta;
    private final Path pastaParciais;
    private final Set<String> reservados = new HashSet<>();
    private final Map<String, String> cacheHash = new ConcurrentHashMap<>();
    private Lista lista = new Lista(0, List.of());

    public RepositorioArquivos(Path pasta) throws IOException {
        this.pasta = pasta.toAbsolutePath().normalize();
        this.pastaParciais = this.pasta.resolveSibling(".uploads_parciais");
        Files.createDirectories(this.pasta);
        Files.createDirectories(pastaParciais);
        try (Stream<Path> sobras = Files.list(pastaParciais)) { // restos de uma execução interrompida
            sobras.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignorada) {
                }
            });
        }
    }

    public Path getPasta() {
        return pasta;
    }

    public synchronized String reservarNome(String nome) {
        String candidato = nome;
        for (int n = 1; ocupado(candidato); n++) {
            candidato = Util.nomeComSufixo(nome, n);
        }
        reservados.add(chave(candidato));
        return candidato;
    }

    private boolean ocupado(String nome) {
        if (reservados.contains(chave(nome)) || Files.exists(pasta.resolve(nome))) {
            return true;
        }
        // no Windows e no macOS "Foto.jpg" e "foto.jpg" são o mesmo arquivo
        return lista.arquivos().stream().anyMatch(a -> a.nome().equalsIgnoreCase(nome));
    }

    public synchronized void liberarNome(String nome) {
        reservados.remove(chave(nome));
    }

    public Path criarTemporario() throws IOException {
        return Files.createTempFile(pastaParciais, "upload-", ".part");
    }

    public synchronized void efetivar(Path temporario, String nomeFinal, String hash) throws IOException {
        Path destino = pasta.resolve(nomeFinal);
        Files.move(temporario, destino, StandardCopyOption.REPLACE_EXISTING);
        reservados.remove(chave(nomeFinal));
        cacheHash.put(chaveHash(destino), hash.toLowerCase(Locale.ROOT));
    }

    public Path localizar(String nome) {
        if (nome == null || nome.isBlank() || nome.contains("/") || nome.contains("\\") || nome.equals("..")) {
            return null; // nunca deixa sair da pasta central
        }
        try {
            Path arquivo = pasta.resolve(nome).normalize();
            if (!pasta.equals(arquivo.getParent()) || !Files.isRegularFile(arquivo)) {
                return null;
            }
            return arquivo;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    public String hash(Path arquivo) throws IOException {
        String chave = chaveHash(arquivo);
        String hash = cacheHash.get(chave);
        if (hash == null) {
            hash = Util.sha256(arquivo);
            cacheHash.put(chave, hash);
        }
        return hash;
    }

    // Retorna true se a pasta mudou (upload, arquivo apagado ou colocado à mão)
    public synchronized boolean atualizarLista() {
        List<InfoArquivo> atual = escanear();
        if (atual.equals(lista.arquivos())) {
            return false;
        }
        lista = new Lista(lista.versao() + 1, atual);
        return true;
    }

    public synchronized Lista getLista() {
        return lista;
    }

    private List<InfoArquivo> escanear() {
        try (Stream<Path> arquivos = Files.list(pasta)) {
            return arquivos
                    .filter(Files::isRegularFile)
                    .filter(p -> !IGNORADOS.contains(p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .map(RepositorioArquivos::info)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(InfoArquivo::nome, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException e) {
            return lista.arquivos();
        }
    }

    private static InfoArquivo info(Path p) {
        try {
            return new InfoArquivo(p.getFileName().toString(), Files.size(p), Files.getLastModifiedTime(p).toMillis());
        } catch (IOException e) {
            return null; // apagado no meio da leitura
        }
    }

    private static String chave(String nome) {
        return nome.toLowerCase(Locale.ROOT);
    }

    private static String chaveHash(Path p) throws IOException {
        return p.getFileName() + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis();
    }
}
