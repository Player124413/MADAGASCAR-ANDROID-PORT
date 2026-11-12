# Histórico de versões

Port por recompilação estática feito com o XboxSwitchPortKit (pasta `kit/`): o Direct3D 8 e o DirectSound do Xbox são reescritos em Vulkan (NVK) e no áudio do Switch.

## 0.1.7 — 2026-11-12

- Desempenho: as threads do jogo, a de renderização e a de áudio agora são distribuídas pelos três núcleos do Switch (antes todas rodavam no núcleo 0).
- Desempenho: o log não grava mais cada espera de sincronização do jogo (eram cerca de 45 linhas por segundo gravadas no cartão SD).
- Removidos contadores e logs de diagnóstico temporários do renderizador.

## 0.1.6 — 2026-11-12

- Desfoque de profundidade (DOF) corrigido: o jogo lê o buffer de profundidade como cor, como no Xbox. O gameplay fica nítido no Switch e só o fundo dos menus e da demo é desfocado, como no original.

## 0.1.0 a 0.1.5

- Primeiro port sobre o XboxSwitchPortKit: recompilação do `default.xbe`, Direct3D 8, áudio, vídeos Bink e menu do port.
- Travamento no carregamento corrigido: o semáforo do streaming de áudio é tratado como recursivo, porque as leituras de arquivo terminam na hora em vez de ficarem pendentes como no DVD.
- Leituras de stream que terminavam na hora não gravavam o número de bytes lidos: a busca de blocos relia o mesmo cabeçalho para sempre.
- Texturas brancas corrigidas (um estágio de textura antigo sem textura ligada era lido no shader).
