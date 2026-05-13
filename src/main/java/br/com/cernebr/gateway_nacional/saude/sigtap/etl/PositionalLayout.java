package br.com.cernebr.gateway_nacional.saude.sigtap.etl;

import java.util.List;

/**
 * Descritor declarativo de um layout posicional do DataSUS.
 *
 * <p>O SIGTAP entrega cada tabela em um {@code .txt} com colunas de largura
 * fixa (sem separador). Cada coluna conhecida tem nome, offset inicial
 * (0-based) e largura. A descrição é declarativa porque o layout muda
 * raras vezes por ano — quando muda, basta editar este descritor sem
 * mexer no parser.</p>
 *
 * <p><b>Fonte do layout:</b> {@code tb_*_layout.txt} embarcado no pacote
 * mensal. Os offsets aqui refletem a competência 2026-05; uma futura
 * mudança de layout dispara a refatoração apenas deste arquivo.</p>
 */
public record PositionalLayout(String tableName, List<Column> columns) {

    public record Column(String name, int offset, int length) {
        public String extract(String row) {
            if (row == null) return null;
            int end = Math.min(offset + length, row.length());
            if (offset >= row.length()) return "";
            return row.substring(offset, end).trim();
        }
    }

    public static final PositionalLayout PROCEDIMENTO = new PositionalLayout("tb_procedimento", List.of(
            new Column("codigo", 0, 10),
            new Column("nome", 10, 250),
            new Column("complexidade", 260, 1),
            new Column("sexo", 261, 1),
            new Column("idadeMinimaDias", 262, 10),
            new Column("idadeMaximaDias", 272, 10),
            new Column("quantidadeMaxima", 282, 4),
            new Column("tipoFinanciamento", 286, 2),
            new Column("valorSh", 288, 10),
            new Column("valorSa", 298, 10),
            new Column("valorSp", 308, 10),
            new Column("dtCompetencia", 318, 6)
    ));

    public static final PositionalLayout CBO = new PositionalLayout("tb_cbo", List.of(
            new Column("codigo", 0, 6),
            new Column("nome", 6, 250)
    ));

    public static final PositionalLayout CID = new PositionalLayout("tb_cid", List.of(
            new Column("codigo", 0, 4),
            new Column("nome", 4, 250)
    ));

    public static final PositionalLayout GRUPO = new PositionalLayout("tb_grupo", List.of(
            new Column("codigo", 0, 2),
            new Column("nome", 2, 250)
    ));

    public static final PositionalLayout SUBGRUPO = new PositionalLayout("tb_sub_grupo", List.of(
            new Column("codigo", 0, 4),
            new Column("grupoCodigo", 0, 2),
            new Column("nome", 4, 250)
    ));

    public static final PositionalLayout FORMA_ORGANIZACAO = new PositionalLayout("tb_forma_organizacao", List.of(
            new Column("codigo", 0, 6),
            new Column("subgrupoCodigo", 0, 4),
            new Column("nome", 6, 250)
    ));

    public static final PositionalLayout PROC_CBO = new PositionalLayout("rl_procedimento_cbo", List.of(
            new Column("procedimentoCodigo", 0, 10),
            new Column("cboCodigo", 10, 6)
    ));

    public static final PositionalLayout PROC_CID = new PositionalLayout("rl_procedimento_cid", List.of(
            new Column("procedimentoCodigo", 0, 10),
            new Column("cidCodigo", 10, 4),
            new Column("obrigatorio", 14, 1)
    ));
}
