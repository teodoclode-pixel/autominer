# Area Miner (Fabric 1.21.4)

Mod w stylu WorldEdit: zaznaczasz obszar i kopiesz go automatycznie.

## Komendy
- `/area set 1` i `/area set 2` – dwa przeciwlegle narozniki (blok, w ktorym stoisz)
- `/area start` – zaczyna kopac narzedziem z reki (od gory do dolu)
- `/area stop` – konczy kopanie
- `/repairall` (lub `/area repairall`) – naprawia wszystkie narzedzia i zbroje w ekwipunku
- `/area reserve <liczba>` – przy ilu pozostalych durach kopanie sie zatrzymuje (domyslnie 100)

Zaznaczac mozna tez rozdzka: `/give @s areaminer:wand`, LPM = pos1, PPM = pos2.

## Kopanie zatrzymuje sie, gdy
- wpiszesz `/area stop`,
- otworzysz czat, nacisniesz ESC (albo otworzysz inne okno, np. ekwipunek) – wymaga moda zainstalowanego tez na kliencie,
- narzedziu zostanie <= rezerwa dura,
- obszar jest wykopany, zmienisz wymiar lub wyjdziesz z gry.

Po `/repairall` wpisz ponownie `/area start`.

## Budowanie
Potrzebujesz JDK 21 i Gradle:

    gradle build

Gotowy jar: `build/libs/areaminer-1.0.0.jar` -> do `mods/` razem z Fabric API.
