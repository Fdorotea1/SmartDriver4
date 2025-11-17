#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Gera uma radiografia do projeto Android em AI_CONTEXT/project_index.txt
- Lista hierárquica das pastas/ficheiros principais (java/kotlin/res/etc.)
- Contagem de linhas e tamanho
- Marcação heurística "provável-ativo" vs "possível-inativo"
- Deteção de nomes de ficheiros duplicados em caminhos diferentes

Uso:
    python generate_index.py                # assume raiz = cwd
    python generate_index.py /caminho/para/projeto

Não altera código do projeto. Apenas escreve em /AI_CONTEXT/project_index.txt
"""

import sys, os, time, hashlib
from datetime import datetime, timedelta

# --- Configuração ---
EXCLUDE_DIRS = {
    "build", ".git", ".gradle", ".idea", ".fleet", ".history",
    ".DS_Store", "captures", "out"
}
INCLUDE_EXTS = {
    ".kt", ".java", ".xml", ".gradle", ".kts", ".pro",
    ".properties", ".json", ".txt", ".md"
}
SOURCE_HINTS = [
    os.path.join("app","src","main","java"),
    os.path.join("app","src","main","kotlin"),
    os.path.join("app","src","main","res"),
    os.path.join("app","src","main","AndroidManifest.xml"),
]
HEURISTIC_INACTIVE_DAYS = 180  # >180 dias sem alterações + pouco conteúdo -> possível inativo
SMALL_FILE_LINES = 20

# --- Helpers ---
def human_size(n):
    for unit in ['B','KB','MB','GB']:
        if n < 1024.0:
            return f"{n:.1f} {unit}"
        n /= 1024.0
    return f"{n:.1f} TB"

def count_lines(path):
    try:
        with open(path, "rb") as f:
            return sum(1 for _ in f)
    except Exception:
        return -1

def file_hash_quick(path, max_bytes=4096):
    try:
        h = hashlib.sha1()
        with open(path, "rb") as f:
            h.update(f.read(max_bytes))
        return h.hexdigest()[:10]
    except Exception:
        return "NA"

def looks_active(path, lines, mtime):
    ext = os.path.splitext(path)[1].lower()
    probable_active = False
    try:
        text = ""
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            text = f.read(4000)  # basta amostra
        if ext in (".kt",".java"):
            # procura tokens típicos de código
            if any(tok in text for tok in ["class ", "object ", "interface ", "fun ", "@Composable", "override fun"]):
                probable_active = True
        elif ext == ".xml":
            if "<layout" in text or "<manifest" in text or "<resources" in text or "<LinearLayout" in text or "<androidx." in text:
                probable_active = True
        else:
            # gradle/kts/manifests e configs tendem a ser relevantes se não forem minúsculos
            if ext in (".gradle",".kts",".pro",".properties",".json"):
                probable_active = lines > 0
    except Exception:
        pass

    # Heurística de “possível inativo”
    age_days = (datetime.now() - datetime.fromtimestamp(mtime)).days
    possible_inactive = (age_days > HEURISTIC_INACTIVE_DAYS) and (0 <= lines <= SMALL_FILE_LINES)

    return probable_active, possible_inactive

def walk_project(root):
    all_files = []
    for dirpath, dirnames, filenames in os.walk(root):
        # filtrar diretórios a excluir
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
        for name in filenames:
            ext = os.path.splitext(name)[1].lower()
            if ext in INCLUDE_EXTS or name == "AndroidManifest.xml":
                path = os.path.join(dirpath, name)
                try:
                    st = os.stat(path)
                except Exception:
                    continue
                rel = os.path.relpath(path, root)
                lines = count_lines(path)
                active, maybe_dead = looks_active(path, lines, st.st_mtime)
                all_files.append({
                    "rel": rel.replace("\\","/"),
                    "size": st.st_size,
                    "mtime": st.st_mtime,
                    "lines": lines,
                    "active": active,
                    "maybe_dead": maybe_dead,
                    "hash": file_hash_quick(path)
                })
    return all_files

def treeify(paths):
    # constrói árvore simples para imprimir hierarquia
    root = {}
    for p in paths:
        parts = p.split("/")
        cur = root
        for i, part in enumerate(parts):
            is_file = (i == len(parts)-1)
            cur = cur.setdefault(part if not is_file else f"· {part}", {})
    return root

def render_tree(d, indent=""):
    lines = []
    keys = sorted(d.keys())
    for i, k in enumerate(keys):
        last = (i == len(keys)-1)
        prefix = "└── " if last else "├── "
        lines.append(indent + prefix + k)
        sub = d[k]
        if sub:
            lines.extend(render_tree(sub, indent + ("    " if last else "│   ")))
    return lines

def write_report(root, files, out_path):
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    # separar por áreas
    java_like = [f for f in files if f["rel"].startswith(("app/src/main/java/","app/src/main/kotlin/"))]
    res_like  = [f for f in files if f["rel"].startswith("app/src/main/res/")]
    others    = [f for f in files if f not in java_like and f not in res_like]

    # duplicados por nome
    name_map = {}
    for f in files:
        base = os.path.basename(f["rel"])
        name_map.setdefault(base, []).append(f)
    duplicates = {k:v for k,v in name_map.items() if len(v) > 1}

    total_size = sum(f["size"] for f in files)
    total_lines = sum(f["lines"] for f in files if f["lines"] >= 0)

    # construir árvores
    java_tree = treeify([f["rel"] for f in java_like])
    res_tree  = treeify([f["rel"] for f in res_like])

    with open(out_path, "w", encoding="utf-8") as w:
        w.write("# SmartDriver — Índice de Projeto (gerado)\n")
        w.write(f"Gerado em: {now}\n")
        w.write(f"Raiz analisada: {root}\n\n")

        w.write("## Sumário\n")
        w.write(f"- Ficheiros analisados: {len(files)}\n")
        w.write(f"- Linhas (aprox.): {total_lines}\n")
        w.write(f"- Tamanho total: {human_size(total_size)}\n")
        w.write(f"- Diretórios ignorados: {', '.join(sorted(EXCLUDE_DIRS))}\n")
        w.write(f"- Extensões incluídas: {', '.join(sorted(INCLUDE_EXTS))}\n\n")

        # Sinais rápidos
        active_count = sum(1 for f in files if f["active"])
        maybe_dead_count = sum(1 for f in files if f["maybe_dead"])
        w.write("## Indicadores heurísticos\n")
        w.write(f"- Provável-ativos: {active_count}\n")
        w.write(f"- Possível-inativos: {maybe_dead_count} (antigos e pequenos)\n\n")

        # Duplicados
        w.write("## Duplicados por nome de ficheiro\n")
        if not duplicates:
            w.write("- (Sem duplicados relevantes)\n\n")
        else:
            for name, arr in sorted(duplicates.items()):
                w.write(f"- {name}:\n")
                for f in sorted(arr, key=lambda x: x["rel"]):
                    w.write(f"    • {f['rel']}  [{f['lines']} linhas, {human_size(f['size'])}]\n")
            w.write("\n")

        # JAVA/KOTLIN TREE
        w.write("## Estrutura: código (java/kotlin)\n")
        if java_like:
            for line in render_tree(java_tree):
                w.write(line + "\n")
        else:
            w.write("(vazio)\n")
        w.write("\n")

        # RES TREE
        w.write("## Estrutura: recursos (res)\n")
        if res_like:
            for line in render_tree(res_tree):
                w.write(line + "\n")
        else:
            w.write("(vazio)\n")
        w.write("\n")

        # Tabela detalhada
        w.write("## Tabela detalhada (top 300 por ordem alfabética)\n")
        header = "linhas | tamanho | mtime-dias | ativo? | poss-inativo? | caminho\n"
        w.write(header)
        w.write("-"*len(header) + "\n")
        files_sorted = sorted(files, key=lambda x: x["rel"])[:300]
        now_ts = time.time()
        for f in files_sorted:
            age_days = int((now_ts - f["mtime"]) / 86400)
            w.write(f"{f['lines']:>6} | {human_size(f['size']):>7} | {age_days:>10} | "
                    f"{'sim' if f['active'] else 'não ':>5} | "
                    f"{'sim' if f['maybe_dead'] else 'não ':>12} | "
                    f"{f['rel']}\n")

        # Notas finais
        w.write("\n## Notas\n")
        w.write("- 'provável-ativo' baseia-se em tokens típicos (class/fun/layout/manifest etc.).\n")
        w.write("- 'possível-inativo' = ficheiro muito antigo e com poucas linhas (heurística).\n")
        w.write("- Se precisares, podemos gerar uma lista negra/whitelist para afinar as heurísticas.\n")

def main():
    project_root = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.getcwd()
    files = walk_project(project_root)

    # garantir pasta AI_CONTEXT
    ai_dir = os.path.join(project_root, "AI_CONTEXT")
    os.makedirs(ai_dir, exist_ok=True)
    out_file = os.path.join(ai_dir, "project_index.txt")

    write_report(project_root, files, out_file)

    print("✔ Índice gerado com sucesso:")
    print(out_file)
    # dica rápida
    print("\nSugestão: inclui também um 'AI_CONTEXT/active_tasks.txt' manual com as tarefas em curso.")

if __name__ == "__main__":
    main()
