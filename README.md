# POAO Live Event As (a) SErvice - PLEASE
App som sender ut live-events via websocket til veilarbpersonflate

Authentication implementert etter: https://devcenter.heroku.com/articles/websocket-security


## Innkommende kommuniksjon (inbound communication)
| Collaborator                | Query/Command/Event | Melding             |
|-----------------------------|---------------------|---------------------|
| Veilarbdialog               | command (REST/POST) | /notify-subscribers |
| veilarbpersonflate          | command (REST/POST) | /ws-auth-ticket     |
| veilarbpersonflate          | Event (Websoocket)  | <auth-ticket>       |

## Utgående kommunikasjon (outbound communication)
| Collaborator                                | Query/Command/Event | Melding                             |
|:--------------------------------------------|:--------------------|:------------------------------------|
| veilarbpersonflate                          | Event (Websocket)   | NY_DIALOGMELDING_FRA_BRUKER_TIL_NAV |
| veilarbpersonflate                          | Event (Websocket)   | AUTHENTICATED                       |
| arbeidsrettet-dialog(TODO, ikke laget enda) | Event (Websocket)   | NY_DIALOGMELDING_FRA_NAV_TIL_BRUKER |