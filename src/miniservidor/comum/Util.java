package miniservidor.comum;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class Util {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private Util() {
    }

    public static int totalFragmentos(long tamanho) {
        if (tamanho <= 0) {
            return 1; // arquivo vazio vai em 1 fragmento sem dados
        }
        long total = (tamanho + Protocolo.TAMANHO_FRAGMENTO - 1) / Protocolo.TAMANHO_FRAGMENTO;
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("arquivo grande demais");
        }
        return (int) total;
    }

    public static String sha256(Path arquivo) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(arquivo)) {
            byte[] buffer = new byte[64 * 1024];
            int lidos;
            while ((lidos = in.read(buffer)) > 0) {
                digest.update(buffer, 0, lidos);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // Nome que veio pela rede: tira o caminho (evita "../../") e o que o Windows não aceita.
    // Retorna null se não sobrar nada.
    public static String sanitizarNome(String nome) {
        if (nome == null) {
            return null;
        }
        int barra = Math.max(nome.lastIndexOf('/'), nome.lastIndexOf('\\'));
        String s = nome.substring(barra + 1);

        StringBuilder limpo = new StringBuilder();
        for (char c : s.toCharArray()) {
            boolean proibido = c < 32 || "<>:\"|?*".indexOf(c) >= 0;
            limpo.append(proibido ? '_' : c);
        }
        s = limpo.toString().strip();
        while (s.endsWith(".") || s.endsWith(" ")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return null;
        }
        String base = s.contains(".") ? s.substring(0, s.indexOf('.')) : s;
        if (base.toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            s = "_" + s;
        }
        if (s.length() > 200) {
            int ponto = s.lastIndexOf('.');
            String extensao = ponto > 0 && s.length() - ponto <= 20 ? s.substring(ponto) : "";
            s = s.substring(0, 200 - extensao.length()) + extensao;
        }
        return s;
    }

    // "foto.jpg", 2 -> "foto (2).jpg"
    public static String nomeComSufixo(String nome, int numero) {
        int ponto = nome.lastIndexOf('.');
        if (ponto <= 0) {
            return nome + " (" + numero + ")";
        }
        return nome.substring(0, ponto) + " (" + numero + ")" + nome.substring(ponto);
    }

    public static Path caminhoLivre(Path pasta, String nome) {
        String limpo = sanitizarNome(nome);
        if (limpo == null) {
            limpo = "arquivo";
        }
        Path candidato = pasta.resolve(limpo);
        for (int n = 1; Files.exists(candidato); n++) {
            candidato = pasta.resolve(nomeComSufixo(limpo, n));
        }
        return candidato;
    }

    public static String formatarTamanho(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] unidades = {"KB", "MB", "GB", "TB"};
        double valor = bytes;
        int i = -1;
        do {
            valor /= 1024;
            i++;
        } while (valor >= 1024 && i < unidades.length - 1);
        return String.format(PT_BR, "%.1f %s", valor, unidades[i]);
    }

    public static String endereco(InetSocketAddress endereco) {
        if (endereco == null) {
            return "?";
        }
        InetAddress ip = endereco.getAddress();
        String host = ip != null ? ip.getHostAddress() : endereco.getHostString();
        return host + ":" + endereco.getPort();
    }

    public static List<String> ipsLocais() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface placa : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!placa.isUp() || placa.isLoopback() || placa.isVirtual()) {
                    continue;
                }
                for (InetAddress ip : Collections.list(placa.getInetAddresses())) {
                    if (ip instanceof Inet4Address && !ip.isLinkLocalAddress()) {
                        ips.add(ip.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignorada) {
        }
        return ips;
    }
}
