package miniservidor.cliente;

@FunctionalInterface
public interface OuvinteProgresso {
    void progresso(long bytesFeitos, long bytesTotal);
}
