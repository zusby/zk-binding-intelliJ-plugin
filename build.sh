#!/bin/bash

# Script per build e test del plugin ZK Binding Support

set -e

echo "================================================"
echo "   ZK Binding Support Plugin - Build Script"
echo "================================================"
echo ""

# Colori
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Funzione di aiuto
show_help() {
    echo "Uso: ./build.sh [opzione]"
    echo ""
    echo "Opzioni:"
    echo "  build       - Build completo del plugin (default)"
    echo "  clean       - Pulizia dei file di build"
    echo "  test        - Avvia IntelliJ con il plugin in sviluppo"
    echo "  install     - Build e mostra istruzioni per l'installazione"
    echo "  help        - Mostra questo messaggio"
    echo ""
}

# Funzione per build
do_build() {
    echo -e "${BLUE}🔨 Building plugin...${NC}"
    ./gradlew clean buildPlugin
    
    if [ $? -eq 0 ]; then
        echo ""
        echo -e "${GREEN}✅ Build completato con successo!${NC}"
        echo ""
        echo "Plugin generato in:"
        echo "  📦 build/distributions/zk-intellij-plugin-1.0.0.zip"
        echo ""
    else
        echo -e "${RED}❌ Build fallito${NC}"
        exit 1
    fi
}

# Funzione per clean
do_clean() {
    echo -e "${BLUE}🧹 Cleaning build files...${NC}"
    ./gradlew clean
    echo -e "${GREEN}✅ Pulizia completata${NC}"
}

# Funzione per test
do_test() {
    echo -e "${BLUE}🚀 Avvio IntelliJ IDEA con il plugin...${NC}"
    echo ""
    echo "Verrà aperta una nuova istanza di IntelliJ con il plugin installato."
    echo "Puoi testare il plugin aprendo un progetto ZK esistente."
    echo ""
    ./gradlew runIde
}

# Funzione per install
do_install() {
    do_build
    
    echo -e "${BLUE}📥 Istruzioni per l'installazione:${NC}"
    echo ""
    echo "1. Apri IntelliJ IDEA"
    echo "2. Vai su: File → Settings → Plugins"
    echo "3. Click sull'icona ⚙️ in alto"
    echo "4. Seleziona 'Install Plugin from Disk...'"
    echo "5. Naviga a:"
    echo "   $(pwd)/build/distributions/zk-intellij-plugin-1.0.0.zip"
    echo "6. Click 'OK' e riavvia IntelliJ"
    echo ""
    echo -e "${GREEN}✅ Il plugin sarà attivo dopo il riavvio!${NC}"
    echo ""
}

# Main
case "${1:-build}" in
    build)
        do_build
        ;;
    clean)
        do_clean
        ;;
    test)
        do_test
        ;;
    install)
        do_install
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        echo -e "${RED}Opzione sconosciuta: $1${NC}"
        echo ""
        show_help
        exit 1
        ;;
esac
