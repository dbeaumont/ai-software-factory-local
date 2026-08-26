# Template de ticket

## Définition du ticket

### Ce que l'équipe doit livrer

#### Résumé

Ajouter GET /customers/{id}

#### Objectif métier

Ajouter un service permettant de récupérer les détails d'un customer. 
Pour le moment, la page retournée ne doit afficher que l'id du customer

## Context

### Cadre de l'évolution

#### Application / domaine

Customer API

#### Comportement actuel

Il n'existe pas encore de consultation d'un customer

#### Contraintes existantes et fichiers pertinents Optionnel

Utiliser CustomerController.java

## Spécifications

### Comportement et qualité attendus

#### Comportement attendu

1. Given : Quand un GET /customers/{id}  est soumis, le système doit exécuter un service qui renvoie uniquement le customer id
2. When : quand l'API GET /customers/{id} est soumise, elle doit renvoyer l'id du customer en réponse
3. Done : la réponse doit être un flux JSon contenant l'id du customer

#### Critères d'acceptation

- cas nominal : la réponse JSon avec l'id du customer est affiché
- cas d'erreur : lorsque l'id de customer demandé n'existe pas, une erreur 404 doit être retournée par l'API