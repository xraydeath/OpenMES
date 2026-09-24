<div align="center">

<img src="assets/icon.svg" alt="Иконка OpenMES" width="200" />

# OpenMES

### Открытый клиент «Колледжа МЭШ» для Android

<br/>

[![Последний релиз](https://img.shields.io/github/v/release/xraydeath/OpenMES?style=for-the-badge&labelColor=0d1117)](https://github.com/xraydeath/OpenMES/releases)
[![Лицензия](https://img.shields.io/badge/license-GPL--3.0-blue?style=for-the-badge&labelColor=0d1117)](LICENSE)

<br/>

[**Скачать**](#download) · [**Возможности**](#features) · [**Сборка**](#build)

</div>

> [!NOTE]
> OpenMES — неофициальный клиент московского электронного дневника для колледжей (СПО),
> написанный с нуля на Jetpack Compose и Material 3 Expressive.
> Нужен аккаунт **mos.ru** студента колледжа.

---

<div align="center">

<h1><a id="features"></a>Возможности</h1>

<table>
  <tr>
    <td width="50%" valign="top">

#### Учёба
- Расписание с листанием по дням и неделям
- Детали урока: тема, кабинет, преподаватель
- Домашние задания с материалами
- Библиотека МЭШ прямо в приложении
- Посещаемость с начала учебного года

</td>
    <td width="50%" valign="top">

#### Оценки
- Оценки по предметам и средний балл
- Итоговые оценки
- Зачётка по курсам
- Калькулятор оценок
- Уведомления о новых оценках

</td>
  </tr>
  <tr>
    <td width="50%" valign="top">

#### Колледж
- Новости
- Сведения о колледже: кураторы, контакты, корпуса
- Профориентация
- Меню питания
- Студенческий билет с QR-кодом

</td>
    <td width="50%" valign="top">

#### Приложение
- Вход через mos.ru, пароль остаётся на портале
- Токены в Android Keystore
- PIN-код и биометрия
- Офлайн-кэш: данные видны без сети
- Material You, светлая и тёмная тема

</td>
  </tr>
</table>

</div>

---

<div align="center">

<h1><a id="download"></a>Скачать</h1>

<h3>APK публикуются на <a href="https://github.com/xraydeath/OpenMES/releases">странице релизов</a>.</h3>

</div>

---

<div align="center">

<h1><a id="build"></a>Сборка из исходников</h1>

</div>

```bash
git clone https://github.com/xraydeath/OpenMES.git
cd OpenMES
./gradlew :app:assembleRelease
```

<div align="center">

<h3>APK появится в <code>app/build/outputs/apk/release/</code>.<br/>Нужны JDK 17–21 и Android SDK (platform 36), minSdk 26 (Android 8.0).</h3>

</div>

---

<div align="center">

<h1>Лицензия</h1>

[GNU GPL v3.0](LICENSE). Часть кода основана на [OctoDiary](https://github.com/OctoDiary) (MIT), подробности в [NOTICE](NOTICE).<br/>
Шрифт Inter — [SIL OFL 1.1](https://github.com/rsms/inter).

</div>
