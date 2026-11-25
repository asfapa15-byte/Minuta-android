# Minuta – aplicație Android pentru minute de ședință

Aplicația înregistrează ședința prin microfonul telefonului, transcrie discuția în română și scrie minuta:
**rezumat, decizii, acțiuni (cu responsabil și termen) și transcrierea completă**.

## Ce face

- Înregistrează în fundal, inclusiv cu ecranul stins. Are pauză/continuare și butoane direct în notificare.
- Taie automat audio în bucăți de 10 minute, așa că merge și la ședințe de câteva ore.
- Transcrie cu OpenAI (`gpt-4o-transcribe`, în limba română).
- Scrie minuta cu Claude și transformă termenele relative („vineri”) în date concrete.
- Poate importa înregistrări existente (meniul ⋮ → *Importă înregistrare*), de exemplu apelurile înregistrate de telefoanele Samsung sau un fișier .m4a/.mp3 trimis de cineva.
- Poți trimite minuta pe e-mail, Teams sau WhatsApp (butonul *Trimite*), o poți copia sau regenera.
- Păstrează istoricul ședințelor pe telefon. Dacă transcrierea se întrerupe (de exemplu fără internet), *Reîncearcă* reia de unde a rămas.

## Ce îți trebuie

1. **Cheie Anthropic** (pentru minută): <https://console.anthropic.com> → API Keys.
2. **Cheie OpenAI** (pentru transcriere): <https://platform.openai.com/api-keys>.

Pune ambele chei în aplicație, la ⋮ → **Setări**. Ambele servicii sunt cu plată după consum. Ca ordin de mărime, o ședință de o oră costă în jur de 0,4 $ pentru transcriere, la care se adaugă câțiva cenți pentru minută. Verifică prețurile actuale pe site-urile lor.

## Cum obții APK-ul

### Varianta A – fără să instalezi nimic (GitHub)
1. Fă-ți un cont gratuit pe <https://github.com> și creează un repository nou (poate fi privat).
2. Urcă tot conținutul acestui folder: *Add file → Upload files*, apoi trage fișierele și folderele, inclusiv folderul `.github`.
3. Mergi în tab-ul **Actions**. Build-ul pornește singur și durează cam 5 minute.
4. Când apare bifa verde, deschide rularea și descarcă **Minuta-apk** de la *Artifacts*.
5. Pe telefon: dezarhivează, deschide `app-debug.apk` și permite instalarea din surse necunoscute.

> Dacă browserul nu urcă folderul `.github` (e ascuns), creează-l manual: *Add file → Create new file*, cu numele `.github/workflows/build.yml`, și copiază conținutul fișierului.

### Varianta B – Android Studio
1. Instalează Android Studio, apoi *Open* → alege acest folder.
2. Așteaptă sincronizarea Gradle.
3. Conectează telefonul (cu *USB debugging* activ) și apasă ▶ Run.

## Bine de știut

- **Apeluri Teams / telefon:** Android nu permite aplicațiilor să înregistreze direct sunetul unui apel. Pune apelul pe difuzor lângă telefon sau importă înregistrarea făcută de aplicația de telefon.
- **Baterie:** la unele telefoane (Samsung, Xiaomi) setează *Baterie → Fără restricții* pentru Minuta, ca sistemul să nu oprească înregistrările lungi.
- **Confidențialitate:** audio și transcrierile rămân pe telefon. Doar audio ajunge la OpenAI pentru transcriere, iar textul ajunge la Anthropic pentru minută. Verifică dacă politica firmei permite folosirea acestor servicii pentru ședințele respective.
- **Legal:** anunță participanții că ședința este înregistrată.

## Structura codului

| Fișier | Rol |
|---|---|
| `RecordingService.kt` | Înregistrarea în fundal (serviciu foreground), segmente de 10 min, notificări |
| `Processor.kt` | Transcriere pe segmente + cererea către Claude pentru minută |
| `Api.kt` | Apelurile HTTP către OpenAI și Anthropic, cu reîncercări |
| `Importer.kt` | Importul fișierelor audio și tăierea celor mari |
| `MainActivity.kt` / `RecordActivity.kt` / `MinuteActivity.kt` / `SettingsActivity.kt` | Ecranele |
