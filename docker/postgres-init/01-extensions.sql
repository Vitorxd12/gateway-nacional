-- Habilita pg_trgm no banco cerne_intel para os índices GIN de busca fuzzy
-- (razão social / objeto da licitação) usados pelo IntelSchemaBootstrap.
-- Executado uma única vez pela imagem oficial do Postgres, como superuser,
-- na primeira criação do volume. O IntelSchemaBootstrap também roda
-- CREATE EXTENSION IF NOT EXISTS pg_trgm — este script é belt-and-suspenders
-- para ambientes onde o usuário da aplicação não tem privilégio de extensão.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
