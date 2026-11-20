#!/bin/sh
# Compila o projeto e gera dist/MiniServidorArquivos.jar (roda em Java 17 ou mais novo).
#   ./build.sh
#   java -jar dist/MiniServidorArquivos.jar servidor
#   java -jar dist/MiniServidorArquivos.jar cliente
set -e
cd "$(dirname "$0")"
rm -rf out/build
mkdir -p out/build dist
javac --release 17 -encoding UTF-8 -d out/build $(find src -name '*.java')
jar --create --file dist/MiniServidorArquivos.jar --main-class miniservidor.Iniciar -C out/build .
echo "Gerado: dist/MiniServidorArquivos.jar"
