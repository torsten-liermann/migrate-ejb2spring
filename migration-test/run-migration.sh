#!/bin/bash
#
# Migration Test Script for EJB to Spring Migration
# Arbeitet im Verzeichnis: /path/to/projects/cargotracker-migration-test
#

# Farben fuer Ausgabe
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color
BOLD='\033[1m'

# Arbeitsverzeichnis (kann via Umgebungsvariable ueberschrieben werden)
WORKDIR="${MIGRATION_WORKDIR:-/path/to/projects/cargotracker-migration-test}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REWRITE_YML="${SCRIPT_DIR}/rewrite.yml"
PATCH_DIR="${WORKDIR}/target/rewrite"
PATCH_FILE="${PATCH_DIR}/rewrite.patch"

# Funktionen
print_header() {
    echo -e "\n${BLUE}${BOLD}========================================${NC}"
    echo -e "${BLUE}${BOLD}  $1${NC}"
    echo -e "${BLUE}${BOLD}========================================${NC}\n"
}

print_step() {
    echo -e "${CYAN}[STEP]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[OK]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

# Pruefe ob Arbeitsverzeichnis existiert
if [ ! -d "$WORKDIR" ]; then
    print_error "Arbeitsverzeichnis existiert nicht: $WORKDIR"
    exit 1
fi

cd "$WORKDIR" || exit 1
print_info "Arbeite in: $(pwd)"

# ============================================
# STEP 1: Reset Worktree
# ============================================
print_header "Step 1: Reset Worktree"

print_step "Entferne generierte Dateien..."
# Entferne generierte Dateien (untracked files die git checkout nicht loescht)
rm -rf src/main/java/de/example 2>/dev/null
rm -f src/main/java/org/eclipse/cargotracker/CargoTrackerApplication.java 2>/dev/null
rm -f src/main/java/org/eclipse/cargotracker/infrastructure/messaging/jms/JmsConfiguration.java 2>/dev/null
rm -f src/main/resources/application.properties 2>/dev/null
rm -f src/test/java/org/eclipse/cargotracker/DevContainersConfig.java 2>/dev/null
rm -f src/test/java/org/eclipse/cargotracker/DevCargoTrackerApplication.java 2>/dev/null
rm -f MIGRATION-REVIEW.md DATASOURCE-MIGRATION.properties rewrite.yml 2>/dev/null
print_success "Generierte Dateien entfernt"

print_step "Fuehre git checkout -- . aus..."
if git checkout -- . 2>/dev/null; then
    print_success "Git checkout erfolgreich"
else
    print_warning "Git checkout fehlgeschlagen (moeglicherweise kein Git-Repo)"
fi

print_step "Fuehre mvn clean -q aus..."
if mvn clean -q 2>/dev/null; then
    print_success "Maven clean erfolgreich"
else
    print_warning "Maven clean fehlgeschlagen"
fi

# ============================================
# STEP 2: Copy rewrite.yml
# ============================================
print_header "Step 2: Copy rewrite.yml"

print_step "Kopiere rewrite.yml von ${REWRITE_YML}..."
if [ -f "$REWRITE_YML" ]; then
    cp "$REWRITE_YML" "$WORKDIR/"
    print_success "rewrite.yml kopiert"
else
    print_error "rewrite.yml nicht gefunden: $REWRITE_YML"
    exit 1
fi

# ============================================
# STEP 3: Run OpenRewrite DryRun
# ============================================
print_header "Step 3: OpenRewrite DryRun"

# Rezept-Konfiguration
RECIPE="${RECIPE:-com.github.rewrite.migration.FullSpringBootMigration}"
# Java-Rezepte aus ejb-to-spring-recipes Modul
RECIPE_JAR="de.example.rewrite:ejb-to-spring-recipes:1.0.0-SNAPSHOT"
print_info "Verwende Rezept: $RECIPE"
print_info "Rezept-JAR: $RECIPE_JAR"

print_step "Fuehre OpenRewrite DryRun aus..."
DRYRUN_OUTPUT=$(mktemp)

# DryRun ausfuehren (ohne set -e, da wir Fehler selbst behandeln)
if mvn -U org.openrewrite.maven:rewrite-maven-plugin:dryRun \
    -Drewrite.activeRecipes="$RECIPE" \
    -Drewrite.recipeArtifactCoordinates="$RECIPE_JAR" 2>&1 | tee "$DRYRUN_OUTPUT"; then
    print_success "DryRun erfolgreich abgeschlossen"
else
    print_warning "DryRun beendet mit Warnungen (kann normal sein)"
fi

# ============================================
# STEP 4: Patch Summary
# ============================================
print_header "Step 4: Patch Summary"

if [ -f "$PATCH_FILE" ]; then
    PATCH_LINES=$(wc -l < "$PATCH_FILE")
    PATCH_SIZE=$(du -h "$PATCH_FILE" | cut -f1)
    PATCH_FILES=$(grep -c "^diff --git" "$PATCH_FILE" 2>/dev/null) || PATCH_FILES=0
    ADDITIONS=$(grep -c "^+" "$PATCH_FILE" 2>/dev/null) || ADDITIONS=0
    DELETIONS=$(grep -c "^-" "$PATCH_FILE" 2>/dev/null) || DELETIONS=0
    
    print_success "Patch gefunden: $PATCH_FILE"
    print_info "Patch-Groesse: $PATCH_SIZE ($PATCH_LINES Zeilen)"
    print_info "Geaenderte Dateien: $PATCH_FILES"
    print_info "Additions: ~$ADDITIONS / Deletions: ~$DELETIONS"
else
    print_warning "Kein Patch generiert (keine Aenderungen?)"
    PATCH_LINES=0
    PATCH_SIZE="0"
    PATCH_FILES=0
fi

rm -f "$DRYRUN_OUTPUT"

# ============================================
# STEP 5: Run OpenRewrite Run
# ============================================
print_header "Step 5: OpenRewrite Run (Apply Changes)"

print_step "Fuehre OpenRewrite Run aus..."
RUN_OUTPUT=$(mktemp)

if mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
    -Drewrite.activeRecipes="$RECIPE" \
    -Drewrite.recipeArtifactCoordinates="$RECIPE_JAR" 2>&1 | tee "$RUN_OUTPUT"; then
    print_success "OpenRewrite Run erfolgreich"
else
    print_warning "OpenRewrite Run beendet mit Warnungen"
fi

rm -f "$RUN_OUTPUT"

# ============================================
# STEP 5.1: Enable JMS/Scheduling (Phase 1.3)
# WICHTIG: Muss als separate Phase laufen, da ScanningRecipes
# Änderungen aus dem gleichen Run nicht sehen!
# ============================================
print_header "Step 5.1: Enable JMS/Scheduling (Phase 1.3)"

print_step "Fuehre Enable JMS/Scheduling aus..."
ENABLE_RECIPE="de.example.rewrite.ejb.EnableJmsAndSchedulingPhase"
print_info "Verwende Enable-Rezept: $ENABLE_RECIPE"

ENABLE_OUTPUT=$(mktemp)

if mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
    -Drewrite.activeRecipes="$ENABLE_RECIPE" \
    -Drewrite.recipeArtifactCoordinates="$RECIPE_JAR" 2>&1 | tee "$ENABLE_OUTPUT"; then
    print_success "Enable JMS/Scheduling erfolgreich"
else
    print_warning "Enable JMS/Scheduling beendet mit Warnungen"
fi

rm -f "$ENABLE_OUTPUT"

# ============================================
# STEP 5a: Property Migration (DataSource + String Properties)
# ============================================
print_header "Step 5a: Property Migration (Phase 1.5)"

print_step "Fuehre Property Migration aus..."
PROP_RECIPE="de.example.rewrite.ejb.PropertyMigration"
print_info "Verwende Property-Rezept: $PROP_RECIPE"

PROP_OUTPUT=$(mktemp)

if mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
    -Drewrite.activeRecipes="$PROP_RECIPE" \
    -Drewrite.recipeArtifactCoordinates="$RECIPE_JAR" 2>&1 | tee "$PROP_OUTPUT"; then
    print_success "Property Migration erfolgreich"
else
    print_warning "Property Migration beendet mit Warnungen"
fi

rm -f "$PROP_OUTPUT"

# ============================================
# STEP 5b: JMS Destination Migration (Third Pass)
# ============================================
print_header "Step 5b: JMS Destination Migration (Phase 2)"

print_step "Fuehre JMS Destination Migration aus..."
JMS_RECIPE="de.example.rewrite.ejb.JmsDestinationMigration"
print_info "Verwende JMS-Rezept: $JMS_RECIPE"

JMS_OUTPUT=$(mktemp)

if mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
    -Drewrite.activeRecipes="$JMS_RECIPE" \
    -Drewrite.recipeArtifactCoordinates="$RECIPE_JAR" 2>&1 | tee "$JMS_OUTPUT"; then
    print_success "JMS Migration erfolgreich"
else
    print_warning "JMS Migration beendet mit Warnungen"
fi

rm -f "$JMS_OUTPUT"

# ============================================
# STEP 5c: Mark Manual Migrations (Phase 2.5)
# ============================================
print_header "Step 5c: Mark Manual Migrations (M7)"

print_step "Fuehre Manual Migration Marking aus..."
MANUAL_RECIPE="de.example.rewrite.ejb.MarkManualMigrations"
print_info "Verwende Manual-Migration-Rezept: $MANUAL_RECIPE"

MANUAL_OUTPUT=$(mktemp)

if mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
    -Drewrite.activeRecipes="$MANUAL_RECIPE" \
    -Drewrite.recipeArtifactCoordinates="$RECIPE_JAR" 2>&1 | tee "$MANUAL_OUTPUT"; then
    print_success "Manual Migration Marking erfolgreich"
else
    print_warning "Manual Migration Marking beendet mit Warnungen"
fi

rm -f "$MANUAL_OUTPUT"

# ============================================
# STEP 5d: Generate Migration Report (Final Pass)
# ============================================
print_header "Step 5d: Generate Migration Report"

print_step "Fuehre OpenRewrite Run fuer MigrationReport aus..."
REPORT_RECIPE="de.example.rewrite.ejb.MigrationReportOnly"
print_info "Verwende Report-Rezept: $REPORT_RECIPE"

REPORT_OUTPUT=$(mktemp)

if mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
    -Drewrite.activeRecipes="$REPORT_RECIPE" \
    -Drewrite.recipeArtifactCoordinates="$RECIPE_JAR" 2>&1 | tee "$REPORT_OUTPUT"; then
    print_success "Migration Report erfolgreich generiert"
else
    print_warning "Migration Report beendet mit Warnungen"
fi

# Pruefe ob MIGRATION-REVIEW.md generiert wurde
if [ -f "$WORKDIR/MIGRATION-REVIEW.md" ]; then
    print_success "MIGRATION-REVIEW.md wurde erstellt"
    REVIEW_ITEMS=$(grep -c "^\- \*\*" "$WORKDIR/MIGRATION-REVIEW.md" 2>/dev/null) || REVIEW_ITEMS=0
    print_info "Review-Items: $REVIEW_ITEMS"
else
    print_warning "MIGRATION-REVIEW.md wurde nicht erstellt (keine @NeedsReview Annotationen?)"
fi

rm -f "$REPORT_OUTPUT"

# ============================================
# STEP 5e: Cycle-2 Idempotency Verification (Codex P2.1h Round 53/54/55)
# ============================================
print_header "Step 5e: Cycle-2 Idempotency Verification"

print_step "Verifiziere, dass zweiter Cycle keine Duplikate erzeugt..."

# Codex P2.1h Round 56: Use EXACT markers from MigrateDataSourceDefinition.java (not "migrated from")
DS_BLOCK_BEGIN="# BEGIN SPRING DATASOURCE CONFIGURATION"
DS_BLOCK_END="# END SPRING DATASOURCE CONFIGURATION"

# Function to count valid BEGIN/END pairs in a file
count_block_pairs() {
    local file="$1"
    local begin_count=$(grep -c "$DS_BLOCK_BEGIN" "$file" 2>/dev/null) || begin_count=0
    local end_count=$(grep -c "$DS_BLOCK_END" "$file" 2>/dev/null) || end_count=0
    # Valid pairs = minimum of begin and end counts
    if [ "$begin_count" -le "$end_count" ]; then
        echo "$begin_count"
    else
        echo "$end_count"
    fi
}

# Codex P2.1h Round 57: Function to get content hash of DS blocks in a file
get_ds_block_hash() {
    local file="$1"
    # Extract all content between BEGIN and END markers and hash it
    sed -n "/$DS_BLOCK_BEGIN/,/$DS_BLOCK_END/p" "$file" 2>/dev/null | md5sum | cut -d' ' -f1
}

# Count valid block pairs BEFORE cycle-2 (multi-module)
DS_BLOCKS_BEFORE=0
UNMATCHED_BEFORE=0
declare -A CONTENT_HASH_BEFORE  # Round 57: Track content hashes
PROPS_FILES=$(find "$WORKDIR" -path "*/src/main/resources/application.properties" 2>/dev/null)
for props_file in $PROPS_FILES; do
    if [ -f "$props_file" ]; then
        pairs=$(count_block_pairs "$props_file")
        begin_count=$(grep -c "$DS_BLOCK_BEGIN" "$props_file" 2>/dev/null) || begin_count=0
        end_count=$(grep -c "$DS_BLOCK_END" "$props_file" 2>/dev/null) || end_count=0
        DS_BLOCKS_BEFORE=$((DS_BLOCKS_BEFORE + pairs))
        # Round 57: Store content hash for diff comparison
        if [ "$pairs" -gt 0 ]; then
            CONTENT_HASH_BEFORE["$props_file"]=$(get_ds_block_hash "$props_file")
        fi
        # Check for unmatched markers
        if [ "$begin_count" -ne "$end_count" ]; then
            print_warning "  $props_file: Unbalanced markers (BEGIN=$begin_count, END=$end_count)"
            UNMATCHED_BEFORE=1
        elif [ "$pairs" -gt 0 ]; then
            print_info "  $props_file: $pairs valid DS-Block pair(s)"
        fi
    fi
done
print_info "Total valid DataSource-Block pairs vor Cycle-2: $DS_BLOCKS_BEFORE"

# Run PropertyMigration again (simulating cycle-2)
print_step "Fuehre PropertyMigration erneut aus (Cycle-2)..."
CYCLE2_OUTPUT=$(mktemp)

if mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
    -Drewrite.activeRecipes="$PROP_RECIPE" \
    -Drewrite.recipeArtifactCoordinates="$RECIPE_JAR" 2>&1 | tee "$CYCLE2_OUTPUT" > /dev/null; then
    print_success "Cycle-2 ausgefuehrt"
else
    print_warning "Cycle-2 beendet mit Warnungen"
fi

rm -f "$CYCLE2_OUTPUT"

# Codex P2.1h Round 56: Re-compute PROPS_FILES after cycle-2 (may have created new files)
PROPS_FILES_AFTER=$(find "$WORKDIR" -path "*/src/main/resources/application.properties" 2>/dev/null)

# Count valid block pairs AFTER cycle-2 (multi-module)
DS_BLOCKS_AFTER=0
UNMATCHED_AFTER=0
for props_file in $PROPS_FILES_AFTER; do
    if [ -f "$props_file" ]; then
        pairs=$(count_block_pairs "$props_file")
        begin_count=$(grep -c "$DS_BLOCK_BEGIN" "$props_file" 2>/dev/null) || begin_count=0
        end_count=$(grep -c "$DS_BLOCK_END" "$props_file" 2>/dev/null) || end_count=0
        DS_BLOCKS_AFTER=$((DS_BLOCKS_AFTER + pairs))
        # Check for unmatched markers
        if [ "$begin_count" -ne "$end_count" ]; then
            print_warning "  $props_file: Unbalanced markers (BEGIN=$begin_count, END=$end_count)"
            UNMATCHED_AFTER=1
        elif [ "$pairs" -gt 0 ]; then
            print_info "  $props_file: $pairs valid DS-Block pair(s)"
        fi
    fi
done
print_info "Total valid DataSource-Block pairs nach Cycle-2: $DS_BLOCKS_AFTER"

# Codex P2.1h Round 57: Check content hashes for duplicate detection
CONTENT_CHANGED=0
for props_file in $PROPS_FILES_AFTER; do
    if [ -f "$props_file" ]; then
        pairs=$(count_block_pairs "$props_file")
        if [ "$pairs" -gt 0 ]; then
            hash_after=$(get_ds_block_hash "$props_file")
            hash_before="${CONTENT_HASH_BEFORE[$props_file]:-}"
            if [ -n "$hash_before" ] && [ "$hash_after" != "$hash_before" ]; then
                print_warning "  $props_file: Content changed (hash before: $hash_before, after: $hash_after)"
                CONTENT_CHANGED=1
            fi
        fi
    fi
done

# Verify no duplication and proper structure
CYCLE2_VERIFIED=true
if [ "$DS_BLOCKS_AFTER" -gt "$DS_BLOCKS_BEFORE" ]; then
    print_error "CYCLE-2 DUPLIKATION ERKANNT! Block-Paare: $DS_BLOCKS_BEFORE -> $DS_BLOCKS_AFTER"
    CYCLE2_VERIFIED=false
elif [ "$UNMATCHED_AFTER" -eq 1 ]; then
    print_error "CYCLE-2 STRUKTUR-FEHLER: Unbalanced BEGIN/END markers detected"
    CYCLE2_VERIFIED=false
elif [ "$CONTENT_CHANGED" -eq 1 ]; then
    print_error "CYCLE-2 CONTENT-AENDERUNG: DS-Block-Inhalt wurde modifiziert (moegl. Duplikation)"
    CYCLE2_VERIFIED=false
elif [ "$DS_BLOCKS_AFTER" -eq "$DS_BLOCKS_BEFORE" ]; then
    print_success "Idempotency verifiziert: Keine Duplikation ($DS_BLOCKS_AFTER Block-Paare, Inhalt unveraendert)"
else
    print_warning "Unerwartete Aenderung: $DS_BLOCKS_BEFORE -> $DS_BLOCKS_AFTER"
fi

# ============================================
# STEP 6: Compile Check
# ============================================
print_header "Step 6: Compile Check"

print_step "Fuehre mvn compile aus..."
COMPILE_OUTPUT=$(mktemp)
COMPILE_EXIT_CODE=0

mvn compile 2>&1 | tee "$COMPILE_OUTPUT" || COMPILE_EXIT_CODE=$?

# Zaehle Compile-Fehler (grep -c gibt 1 bei 0 Treffern, daher spezielle Behandlung)
COMPILE_ERRORS=$(grep -c "\[ERROR\].*error:" "$COMPILE_OUTPUT" 2>/dev/null) || COMPILE_ERRORS=0
# Alternative: Zaehle alle ERROR-Zeilen die wie Compile-Fehler aussehen
if [ "$COMPILE_ERRORS" -eq 0 ]; then
    COMPILE_ERRORS=$(grep -c "^\[ERROR\]" "$COMPILE_OUTPUT" 2>/dev/null) || COMPILE_ERRORS=0
fi

if [ $COMPILE_EXIT_CODE -eq 0 ]; then
    print_success "Kompilierung erfolgreich!"
    COMPILE_ERRORS=0
else
    print_error "Kompilierung fehlgeschlagen mit $COMPILE_ERRORS Fehler(n)"
fi

rm -f "$COMPILE_OUTPUT"

# ============================================
# SUMMARY
# ============================================
print_header "ZUSAMMENFASSUNG"

echo -e "${BOLD}Ergebnisse:${NC}"
echo -e "  ${CYAN}Patch-Pfad:${NC}        ${PATCH_FILE}"
echo -e "  ${CYAN}Patch-Groesse:${NC}     ${PATCH_SIZE:-N/A} (${PATCH_LINES:-0} Zeilen)"
echo -e "  ${CYAN}Geaenderte Dateien:${NC} ${PATCH_FILES:-0}"

if [ $COMPILE_EXIT_CODE -eq 0 ]; then
    echo -e "  ${CYAN}Compile-Fehler:${NC}    ${GREEN}0 (Erfolgreich!)${NC}"
else
    echo -e "  ${CYAN}Compile-Fehler:${NC}    ${RED}${COMPILE_ERRORS}${NC}"
fi

# Cycle-2 Verification Info (Codex P2.1h Round 53)
if [ "$CYCLE2_VERIFIED" = true ]; then
    echo -e "  ${CYAN}Cycle-2 Idempotency:${NC} ${GREEN}Verifiziert${NC}"
else
    echo -e "  ${CYAN}Cycle-2 Idempotency:${NC} ${RED}FEHLER - Duplikation erkannt${NC}"
fi

# Migration Review Info
if [ -f "$WORKDIR/MIGRATION-REVIEW.md" ]; then
    REVIEW_COUNT=$(grep -c "^\- \*\*" "$WORKDIR/MIGRATION-REVIEW.md" 2>/dev/null) || REVIEW_COUNT=0
    echo -e "  ${CYAN}Review-Items:${NC}      ${YELLOW}${REVIEW_COUNT}${NC} (siehe MIGRATION-REVIEW.md)"
fi

echo ""
if [ $COMPILE_EXIT_CODE -eq 0 ] && [ "$CYCLE2_VERIFIED" = true ]; then
    echo -e "${GREEN}${BOLD}Migration erfolgreich abgeschlossen!${NC}"
    exit 0
elif [ $COMPILE_EXIT_CODE -eq 0 ]; then
    echo -e "${YELLOW}${BOLD}Migration abgeschlossen, aber Cycle-2 Idempotency NICHT verifiziert.${NC}"
    exit 1
else
    echo -e "${YELLOW}${BOLD}Migration abgeschlossen mit Compile-Fehlern.${NC}"
    echo -e "${YELLOW}Bitte pruefen Sie die Fehler und passen Sie ggf. die Rezepte an.${NC}"
    exit 1
fi
