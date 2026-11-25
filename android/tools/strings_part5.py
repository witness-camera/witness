# -*- coding: utf-8 -*-
"""Provider-aware Protect Footage copy.

The iOS build recently generalized this screen per storage backend (a WebDAV
branch and a generic %@-parameterized branch for OAuth backends), and its
Localizable.xcstrings has no it/es/fr/de translations for the new copy yet -
this is a genuine gap in the iOS source, not something missed on the Android
side. These are original translations, matching the app's existing tone.
"""
import strings_part4  # noqa: F401  (registers every part)
from strings_part1 import STRINGS, src, new

new("protect_what_body",
    "Witness never deletes your recordings, there is no delete button anywhere in this app, and it only ever adds files to your storage account.",
    "Witness non elimina mai le tue registrazioni, non esiste alcun pulsante di eliminazione in questa app, e aggiunge soltanto file al tuo account di archiviazione.",
    "Witness nunca elimina tus grabaciones, no hay ningún botón de eliminar en esta app, y solo añade archivos a tu cuenta de almacenamiento.",
    "Witness ne supprime jamais vos enregistrements, il n'y a aucun bouton de suppression nulle part dans cette appli, et elle ne fait qu'ajouter des fichiers à votre compte de stockage.",
    "Witness löscht deine Aufnahmen nie, es gibt in dieser App nirgends eine Löschschaltfläche, und sie fügt deinem Speicherkonto nur Dateien hinzu.")

new("protect_webdav_risk_body",
    "Your footage uploads to the server you configured. Anyone who can sign in to that server — through its own web interface, another app, or a different device — can delete files there, the same as with any account. WebDAV has no sharing feature, so emergency contacts are never granted a view to lose in the first place.",
    "I tuoi filmati vengono caricati sul server che hai configurato. Chiunque possa accedere a quel server, tramite la sua interfaccia web, un'altra app o un dispositivo diverso, può eliminarvi i file, come con qualsiasi account. WebDAV non ha alcuna funzione di condivisione, quindi i contatti di emergenza non ricevono mai un accesso da perdere.",
    "Tus grabaciones se suben al servidor que configuraste. Cualquiera que pueda iniciar sesión en ese servidor, mediante su propia interfaz web, otra app o un dispositivo distinto, puede eliminar archivos allí, igual que con cualquier cuenta. WebDAV no tiene función de compartir, así que los contactos de emergencia nunca reciben un acceso que puedan perder.",
    "Vos vidéos sont envoyées vers le serveur que vous avez configuré. Quiconque peut se connecter à ce serveur, via son interface web, une autre appli ou un autre appareil, peut y supprimer des fichiers, comme pour n'importe quel compte. WebDAV n'a aucune fonction de partage, les contacts d'urgence ne reçoivent donc jamais d'accès qu'ils pourraient perdre.",
    "Deine Aufnahmen werden auf den von dir eingerichteten Server hochgeladen. Wer sich bei diesem Server anmelden kann — über dessen eigene Weboberfläche, eine andere App oder ein anderes Gerät — kann dort Dateien löschen, genau wie bei jedem anderen Konto. WebDAV kennt keine Freigabefunktion, Notfallkontakte erhalten also von vornherein nie einen Zugriff, den sie verlieren könnten.")

new("protect_webdav_mitigation_header",
    "Reducing the risk (optional)", "Ridurre il rischio (facoltativo)", "Reducir el riesgo (opcional)",
    "Réduire le risque (facultatif)", "Das Risiko verringern (optional)")
new("protect_webdav_mitigation_body",
    "Use credentials on that server dedicated to Witness (an app password, where the server offers one) rather than your main account password, and don't sign in to that same server from apps on this phone. Then there's no way to reach it from the phone to delete anything.",
    "Usa sul server credenziali dedicate a Witness (una password per app, se il server la offre) invece della password principale dell'account, e non accedere a quello stesso server da altre app su questo telefono. In questo modo non c'è modo di raggiungerlo dal telefono per eliminare qualcosa.",
    "Usa en ese servidor credenciales dedicadas a Witness (una contraseña de aplicación, si el servidor la ofrece) en lugar de la contraseña principal de tu cuenta, y no inicies sesión en ese mismo servidor desde otras apps de este teléfono. Así no habrá forma de llegar a él desde el teléfono para eliminar nada.",
    "Utilisez sur ce serveur des identifiants dédiés à Witness (un mot de passe d'application, si le serveur le propose) plutôt que le mot de passe principal de votre compte, et ne vous connectez pas à ce même serveur depuis d'autres applis de ce téléphone. Il n'y aura alors aucun moyen d'y accéder depuis le téléphone pour supprimer quoi que ce soit.",
    "Verwende auf diesem Server für Witness eigene Zugangsdaten (ein App-Passwort, sofern der Server eines anbietet) statt deines Hauptkontopassworts, und melde dich auf demselben Server nicht über andere Apps auf diesem Telefon an. Dann gibt es keine Möglichkeit, ihn vom Telefon aus zu erreichen, um etwas zu löschen.")

new("protect_generic_risk_body",
    "Your footage uploads to your own %1$s account. There, the account owner can always delete their files. So if someone has your unlocked phone and can open the %1$s app or its website — or, for Google, Gmail too, since it shares the same sign-in — they can delete the recordings there, and deleting them removes access for your emergency contacts too.",
    "I tuoi filmati vengono caricati sul tuo account %1$s. Lì, il titolare dell'account può sempre eliminare i propri file. Quindi, se qualcuno ha il tuo telefono sbloccato e può aprire l'app %1$s o il suo sito web, o per Google anche Gmail, poiché condivide lo stesso accesso, può eliminare lì le registrazioni, e farlo toglie l'accesso anche ai tuoi contatti di emergenza.",
    "Tus grabaciones se suben a tu propia cuenta de %1$s. Allí, el titular de la cuenta siempre puede eliminar sus archivos. Así que si alguien tiene tu teléfono desbloqueado y puede abrir la app de %1$s o su web, o para Google, también Gmail, ya que comparte el mismo inicio de sesión, puede eliminar allí las grabaciones, y eso quita también el acceso a tus contactos de emergencia.",
    "Vos vidéos sont envoyées vers votre propre compte %1$s. Là-bas, le titulaire du compte peut toujours supprimer ses fichiers. Donc si quelqu'un a votre téléphone déverrouillé et peut ouvrir l'appli %1$s ou son site, ou pour Google, Gmail aussi, puisqu'il partage la même connexion, il peut y supprimer les enregistrements, ce qui retire aussi l'accès de vos contacts d'urgence.",
    "Deine Aufnahmen werden in dein eigenes %1$s-Konto hochgeladen. Dort kann der Kontoinhaber seine Dateien immer löschen. Wenn also jemand dein entsperrtes Telefon hat und die %1$s-App oder deren Website öffnen kann — bei Google auch Gmail, da es dieselbe Anmeldung nutzt —, kann er die Aufnahmen dort löschen, wodurch auch deine Notfallkontakte den Zugriff verlieren.")
new("protect_generic_sharing_note",
    "Sharing with an emergency contact does not, by itself, give them a separate copy: it grants them a view of your file. If your copy is deleted, their view disappears with it (though deleted files usually sit in a trash/recently-deleted folder for a while first, unless that is also emptied).",
    "Condividere con un contatto di emergenza non gli dà di per sé una copia separata: gli concede solo una vista sul tuo file. Se la tua copia viene eliminata, anche la sua vista scompare (anche se i file eliminati di solito restano prima per un po' nel cestino, a meno che non venga svuotato anche quello).",
    "Compartir con un contacto de emergencia no le da, por sí solo, una copia aparte: le concede una vista de tu archivo. Si tu copia se elimina, su vista desaparece con ella (aunque los archivos eliminados suelen quedarse un tiempo en una papelera, a menos que también se vacíe).",
    "Partager avec un contact d'urgence ne lui donne pas, en soi, une copie séparée : cela lui accorde une vue sur votre fichier. Si votre copie est supprimée, sa vue disparaît avec elle (les fichiers supprimés restent généralement un moment dans une corbeille, sauf si celle-ci est aussi vidée).",
    "Das Teilen mit einem Notfallkontakt gibt ihm für sich genommen keine eigene Kopie: Es gewährt ihm nur eine Ansicht deiner Datei. Wird deine Kopie gelöscht, verschwindet auch seine Ansicht damit (gelöschte Dateien liegen meist noch eine Weile im Papierkorb, sofern dieser nicht ebenfalls geleert wird).")

new("protect_dedicated_account_title",
    "Use a dedicated account", "Usa un account dedicato", "Usa una cuenta dedicada",
    "Utilisez un compte dédié", "Verwende ein eigenes Konto")
new("protect_generic_dedicated_account_body",
    "Create a %1$s account used only for Witness, and sign in to it here. Crucially, do not add that account to your phone's system accounts, any of that provider's apps, or any browser you're signed into. Then there is no way to open that account from your phone to delete anything, it simply isn't reachable through the phone's apps. This app keeps its own private login and offers no way to view or delete the footage.",
    "Crea un account %1$s usato solo per Witness e accedi qui con quello. Fondamentale: non aggiungere quell'account agli account di sistema del telefono, alle app di quel provider, né a un browser in cui hai effettuato l'accesso. Così non c'è modo di aprire quell'account dal telefono per eliminare qualcosa: semplicemente non è raggiungibile dalle app del telefono. Questa app mantiene il proprio accesso privato e non offre alcun modo di vedere o eliminare i filmati.",
    "Crea una cuenta de %1$s usada solo para Witness e inicia sesión aquí con ella. Fundamental: no añadas esa cuenta a las cuentas del sistema de tu teléfono, a ninguna app de ese proveedor, ni a ningún navegador en el que hayas iniciado sesión. Así no habrá forma de abrir esa cuenta desde el teléfono para eliminar nada: sencillamente no es accesible desde las apps del teléfono. Esta app mantiene su propio acceso privado y no ofrece ninguna forma de ver ni eliminar las grabaciones.",
    "Créez un compte %1$s utilisé uniquement pour Witness et connectez-vous avec ici. Essentiel : n'ajoutez pas ce compte aux comptes système de votre téléphone, à aucune appli de ce fournisseur, ni à un navigateur où vous êtes connecté. Ainsi, il n'y a aucun moyen d'ouvrir ce compte depuis le téléphone pour supprimer quoi que ce soit : il n'est tout simplement pas accessible via les applis du téléphone. Cette appli conserve sa propre connexion privée et n'offre aucun moyen de voir ou de supprimer les vidéos.",
    "Erstelle ein %1$s-Konto, das nur für Witness verwendet wird, und melde dich hier damit an. Entscheidend: Füge dieses Konto nicht den Systemkonten deines Telefons, einer App dieses Anbieters oder einem Browser hinzu, in dem du angemeldet bist. Dann gibt es keine Möglichkeit, dieses Konto vom Telefon aus zu öffnen und etwas zu löschen — es ist über die Apps des Telefons schlicht nicht erreichbar. Diese App behält ihre eigene private Anmeldung und bietet keinerlei Möglichkeit, die Aufnahmen anzusehen oder zu löschen.")

new("protect_own_copy_generic_body",
    "When an emergency contact receives the shared folder, they can save their own copy of the footage into their own account. That copy belongs to them and cannot be deleted from your phone or account.",
    "Quando un contatto di emergenza riceve la cartella condivisa, può salvare una propria copia dei filmati nel proprio account. Quella copia gli appartiene e non può essere eliminata dal tuo telefono o account.",
    "Cuando un contacto de emergencia recibe la carpeta compartida, puede guardar su propia copia de las grabaciones en su propia cuenta. Esa copia le pertenece y no se puede eliminar desde tu teléfono ni tu cuenta.",
    "Lorsqu'un contact d'urgence reçoit le dossier partagé, il peut enregistrer sa propre copie des vidéos dans son propre compte. Cette copie lui appartient et ne peut pas être supprimée depuis votre téléphone ou votre compte.",
    "Wenn ein Notfallkontakt den freigegebenen Ordner erhält, kann er eine eigene Kopie der Aufnahmen in sein eigenes Konto speichern. Diese Kopie gehört ihm und kann nicht von deinem Telefon oder Konto aus gelöscht werden.")

new("protect_optional_footer_generic",
    "These steps are optional. If you'd rather use your everyday account, Witness still works exactly the same, this only affects how hard it is for someone with your phone to delete footage after the fact.",
    "Questi passaggi sono facoltativi. Se preferisci usare il tuo account di tutti i giorni, Witness funziona comunque esattamente allo stesso modo: questo influisce solo su quanto sia difficile, per chi ha il tuo telefono, eliminare i filmati dopo i fatti.",
    "Estos pasos son opcionales. Si prefieres usar tu cuenta de todos los días, Witness sigue funcionando exactamente igual: esto solo afecta a lo difícil que le resulte a alguien con tu teléfono eliminar las grabaciones después de los hechos.",
    "Ces étapes sont facultatives. Si vous préférez utiliser votre compte habituel, Witness fonctionne exactement pareil : cela n'affecte que la difficulté, pour quelqu'un possédant votre téléphone, de supprimer les vidéos après coup.",
    "Diese Schritte sind optional. Wenn du lieber dein alltägliches Konto verwendest, funktioniert Witness trotzdem genauso — das betrifft nur, wie schwer es für jemanden mit deinem Telefon ist, Aufnahmen im Nachhinein zu löschen.")

new("protect_your_cloud_account_fallback",
    "your cloud account", "il tuo account cloud", "tu cuenta en la nube",
    "votre compte cloud", "dein Cloud-Konto")

new("settings_protect_footer",
    "Witness never deletes your recordings from cloud storage itself. But anyone holding your unlocked phone could delete them through that account's own app or website. Learn how to guard against that.",
    "Witness non elimina mai le tue registrazioni dallo spazio cloud stesso. Ma chiunque abbia il tuo telefono sbloccato potrebbe eliminarle tramite l'app o il sito web di quell'account. Scopri come proteggerti.",
    "Witness nunca elimina tus grabaciones del almacenamiento en la nube en sí mismo. Pero cualquiera que tenga tu teléfono desbloqueado podría eliminarlas mediante la app o el sitio web de esa cuenta. Descubre cómo protegerte.",
    "Witness ne supprime jamais vos enregistrements du stockage cloud lui-même. Mais quiconque possède votre téléphone déverrouillé pourrait les supprimer via l'appli ou le site de ce compte. Découvrez comment vous en protéger.",
    "Witness löscht deine Aufnahmen nie selbst aus dem Cloud-Speicher. Aber jeder mit deinem entsperrten Telefon könnte sie über die App oder Website dieses Kontos löschen. Erfahre, wie du dich davor schützt.")
