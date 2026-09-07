# Instructions de développement — TypeScript (Angular / React) & Java Spring Boot

## Règle fondamentale

Ces instructions s'appliquent à tout développement dans ce projet. Elles ne sont pas optionnelles. Si une règle entre en conflit avec une demande fonctionnelle, signale le conflit et propose une alternative conforme avant d'écrire du code.

---

## TypeScript / Angular / React

Les règles de typage strict, d'immutabilité et de gestion des erreurs ci-dessous s'appliquent **identiquement** aux deux stacks frontend autorisées : Angular et React. Angular et React sont des choix équivalents — chaque projet retient l'une des deux stacks (justifiée si besoin par un ADR, ex. ADR-0006 pour macmarket qui acte Angular) et ne mélange pas les deux au sein d'une même application. Seules les règles d'architecture (composants, gestion d'état, cycle de vie) diffèrent selon la stack retenue et sont décrites dans des sous-sections dédiées.

### Typage strict — aucune exception

- **Interdit** : `any`, `object` non typé, cast abusif via `as unknown as X`
- Tout paramètre, retour de fonction, variable et propriété de classe doit avoir un type explicite
- Utiliser `unknown` à la place de `any` lorsqu'un type est réellement inconnu, puis affiner avec un type guard
- Activer et respecter `strict: true` dans `tsconfig.json` (inclut `strictNullChecks`, `noImplicitAny`, `strictFunctionTypes`)
- Préférer les `interface` pour les contrats publics et les `type` pour les unions, intersections et alias

```typescript
// INTERDIT
const data: any = fetchData();
function process(input): void { }

// REQUIS
const data: UserDto = fetchData();
function process(input: string): void { }
```

### Immutabilité — aucune mutation directe

- Toute propriété d'objet déclarée dans une `interface` ou un `type` doit être `readonly`
- Les tableaux exposés en propriété de classe ou en état doivent être `readonly T[]` ou `ReadonlyArray<T>`
- Ne jamais muter un objet ou un tableau en place : utiliser le spread `{...obj}`, `[...arr]`, `map`, `filter`, `reduce`
- Utiliser `as const` pour les littéraux constants
- Ne jamais utiliser `push`, `pop`, `splice`, `sort`, `reverse`, `delete` sur un objet partagé — créer un nouveau tableau/objet

```typescript
// INTERDIT
user.name = 'Alice';
items.push(newItem);
items.sort((a, b) => a.id - b.id);

// REQUIS
const updatedUser: User = { ...user, name: 'Alice' };
const updatedItems: readonly Item[] = [...items, newItem];
const sortedItems: readonly Item[] = [...items].sort((a, b) => a.id - b.id);
```

```typescript
// Interface : toutes les propriétés readonly
interface User {
  readonly id: number;
  readonly name: string;
  readonly email: string;
}

// Constante immuable
const ROLES = ['ADMIN', 'USER', 'VIEWER'] as const;
type Role = typeof ROLES[number];
```

### Architecture Angular

- Un composant = une responsabilité unique (SRP)
- Les composants ne contiennent pas de logique métier : déléguer aux services
- Les services sont injectables avec `providedIn: 'root'` ou au niveau du module approprié
- Utiliser les `Signals` Angular (si version >= 17) pour la gestion d'état réactif
- Utiliser `inject()` plutôt que l'injection via constructeur pour les nouveaux composants standalone
- Les composants standalone sont la norme ; éviter les NgModules sauf contrainte existante
- Les `Observable` doivent être désouscrits : utiliser `takeUntilDestroyed()` ou `async` pipe
- Ne jamais souscrire manuellement dans un composant sans gestion du cycle de vie

```typescript
// REQUIS — composant standalone typé et immutable
@Component({
  selector: 'app-user-card',
  standalone: true,
  template: `...`
})
export class UserCardComponent {
  private readonly userService = inject(UserService);

  readonly user: Signal<User | null> = toSignal(
    this.userService.getUser(),
    { initialValue: null }
  );
}
```

### Architecture React

- Un composant = une responsabilité unique (SRP)
- Composants **fonctionnels uniquement** — pas de class components
- Les composants ne contiennent pas de logique métier ni d'appels réseau directs : déléguer à des **custom hooks** (`use-xxx.ts`) ou à des clients de service dédiés
- État serveur (données distantes) : utiliser **TanStack Query** — jamais de `useEffect` + `fetch` manuel pour du data-fetching
- État client partagé : **Zustand** (ou Context API pour un état simple, local à un sous-arbre), toujours mis à jour de façon immuable (spread `{...state}` / `[...array]`) — jamais de mutation directe du store
- `useEffect` : toujours nettoyer abonnements/timers via la fonction de retour (cleanup) — équivalent du `takeUntilDestroyed` Angular
- Un hook custom = une responsabilité, nommé `useXxx`
- Props typées explicitement via une `interface XxxProps` en `readonly` — jamais de props implicites `any`

```typescript
// REQUIS — composant fonctionnel typé et immutable
interface UserCardProps {
  readonly userId: string;
}

export function UserCardComponent({ userId }: UserCardProps): JSX.Element {
  const { data: user } = useUser(userId); // custom hook — délègue la logique/fetch

  return <div>{user?.name}</div>;
}

// REQUIS — custom hook : logique et accès data isolés du composant
function useUser(userId: string) {
  return useQuery({
    queryKey: ['user', userId],
    queryFn: () => fetchUser(userId),
  });
}

// REQUIS — store Zustand : mise à jour immuable
interface CartState {
  readonly items: readonly CartItem[];
  addItem: (item: CartItem) => void;
}

const useCartStore = create<CartState>((set) => ({
  items: [],
  addItem: (item) => set((state) => ({ items: [...state.items, item] })),
}));
```

### Gestion des erreurs TypeScript

- Pas de `try/catch` vides
- Typer explicitement les erreurs capturées : `catch (error: unknown)`
- Utiliser des types résultat (`Result<T, E>`) pour les opérations faillibles plutôt que des exceptions implicites

### Conventions de nommage

| Élément | Angular | React |
|---|---|---|
| Composant | `PascalCase` + suffixe `Component` | `PascalCase` (fichier `PascalCase.tsx`) |
| Service / logique partagée | `PascalCase` + suffixe `Service` | Hook `useXxx` (fichier `use-xxx.ts`) ou client de service `PascalCase` |
| Store d'état global | *(service + Signal)* | `camelCase` + suffixe `Store` (ex. `cartStore`), fichier `kebab-case-store.ts` |
| Interface/Type | `PascalCase` | `PascalCase` |
| Enum | `PascalCase`, valeurs `SCREAMING_SNAKE_CASE` | `PascalCase`, valeurs `SCREAMING_SNAKE_CASE` |
| Variable/Propriété | `camelCase` | `camelCase` |
| Constante globale | `SCREAMING_SNAKE_CASE` | `SCREAMING_SNAKE_CASE` |
| Fichier | `kebab-case.type.ts` | Composants `PascalCase.tsx` ; hooks/stores/utilitaires `kebab-case.ts` |

---

## Java / Spring Boot — Architecture DDD

### Principe directeur

Le code est organisé par **Bounded Context** (sous-domaine métier), pas par type technique. Chaque bounded context est autonome et expose ses fonctionnalités via des ports explicites. La règle de dépendance est stricte : les couches internes (domain) n'ont aucune dépendance vers les couches externes (infrastructure, presentation).

```
Presentation → Application → Domain ← Infrastructure
```

### Structure de packages

Chaque bounded context suit cette structure interne. Un projet contient un ou plusieurs bounded contexts selon la complexité du domaine.

```
src/main/java/com/example/
└── [boundedcontext]/                    — ex: user, order, billing
    ├── domain/
    │   ├── model/                       — Agrégats, Entités, Value Objects
    │   ├── event/                       — Domain Events
    │   ├── repository/                  — Interfaces de repository (ports sortants)
    │   └── service/                     — Domain Services (logique multi-agrégats)
    ├── application/
    │   ├── command/                     — Commands + CommandHandlers
    │   ├── query/                       — Queries + QueryHandlers
    │   └── service/                     — Application Services (orchestration)
    ├── infrastructure/
    │   ├── persistence/                 — Implémentations JPA des repositories
    │   │   ├── entity/                  — Entités JPA (séparées du domaine)
    │   │   ├── repository/              — Spring Data JPA interfaces
    │   │   └── mapper/                  — Mapper domain ↔ JPA entity
    │   ├── messaging/                   — Publication et consommation d'events
    │   └── external/                    — Adapters vers APIs tierces (ACL)
    └── presentation/
        ├── rest/                        — @RestController
        └── dto/                         — Request/Response DTOs + Mappers
```

### Couche Domain — règles absolues

**Aucune dépendance Spring, JPA ou framework dans le domaine.** Le domaine est du Java pur.

#### Agrégat et Racine d'agrégat

- Chaque agrégat a une **racine** (`AggregateRoot`) : seule classe accessible depuis l'extérieur
- Toutes les modifications d'état passent par des méthodes de la racine — pas de setters publics
- La racine garantit les invariants métier : les valider dans chaque méthode de comportement
- Les entités internes à l'agrégat ne sont jamais exposées directement à l'extérieur
- L'agrégat publie des `DomainEvent` après chaque changement d'état significatif

```java
// REQUIS — Racine d'agrégat sans setters, avec invariants
public class Order {

    private final OrderId id;
    private final CustomerId customerId;
    private OrderStatus status;
    private final List<OrderLine> lines;
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    // Constructeur privé — création uniquement via factory method
    private Order(OrderId id, CustomerId customerId, List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new DomainException("Une commande doit contenir au moins une ligne");
        }
        this.id = id;
        this.customerId = customerId;
        this.lines = new ArrayList<>(lines);
        this.status = OrderStatus.DRAFT;
    }

    // Factory method — point d'entrée explicite
    public static Order create(CustomerId customerId, List<OrderLine> lines) {
        Order order = new Order(OrderId.generate(), customerId, lines);
        order.domainEvents.add(new OrderCreatedEvent(order.id, order.customerId));
        return order;
    }

    // Comportement métier avec invariant
    public void confirm() {
        if (this.status != OrderStatus.DRAFT) {
            throw new DomainException("Seule une commande en brouillon peut être confirmée");
        }
        this.status = OrderStatus.CONFIRMED;
        this.domainEvents.add(new OrderConfirmedEvent(this.id));
    }

    // Pas de setters — getters en lecture seule
    public OrderId getId() { return id; }
    public OrderStatus getStatus() { return status; }
    public List<OrderLine> getLines() { return Collections.unmodifiableList(lines); }
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }
}
```

#### Value Objects

- Immuables — utiliser `record` Java systématiquement
- Égalité basée sur la valeur, pas sur l'identité
- Auto-validants : le constructeur lève une `DomainException` si la valeur est invalide
- Typage fort pour les IDs : pas de `Long` ou `String` nus — wrapper dans un Value Object

```java
// REQUIS — ID fortement typé, immuable
public record OrderId(UUID value) {
    public OrderId {
        Objects.requireNonNull(value, "L'identifiant de commande est obligatoire");
    }
    public static OrderId generate() {
        return new OrderId(UUID.randomUUID());
    }
    public static OrderId of(UUID value) {
        return new OrderId(value);
    }
}

// REQUIS — Value Object avec validation métier
public record Email(String value) {
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[^@]+@[^@]+\\.[^@]+$");

    public Email {
        if (value == null || !EMAIL_PATTERN.matcher(value).matches()) {
            throw new DomainException("Adresse email invalide : " + value);
        }
        value = value.toLowerCase();
    }
}

// INTERDIT — ID nu sans sémantique
public class Order {
    private Long id;        // ← interdit
    private String email;   // ← interdit
}
```

#### Domain Events

- Chaque changement d'état significatif produit un `DomainEvent`
- Les events sont immuables (`record`) et contiennent l'heure de l'événement
- Ils sont collectés par la racine d'agrégat et publiés par la couche application après persistence

```java
// REQUIS — Event immuable
public record OrderConfirmedEvent(
    OrderId orderId,
    Instant occurredOn
) implements DomainEvent {
    public OrderConfirmedEvent(OrderId orderId) {
        this(orderId, Instant.now());
    }
}

// Interface marqueur dans le domaine
public interface DomainEvent {
    Instant occurredOn();
}
```

#### Repository — interfaces dans le domaine

- L'interface de repository appartient au **domaine**, pas à l'infrastructure
- Elle parle le langage du domaine : pas de `findById(Long)`, mais `findById(OrderId)`
- Elle ne retourne jamais d'entités JPA — uniquement des objets du domaine

```java
// REQUIS — Interface dans domain/repository
public interface OrderRepository {
    void save(Order order);
    Optional<Order> findById(OrderId id);
    List<Order> findByCustomerId(CustomerId customerId);
}

// INTERDIT — dépendance JPA dans le domaine
public interface OrderRepository extends JpaRepository<Order, Long> { } // ← interdit
```

#### Domain Service

- Logique métier qui implique plusieurs agrégats et ne peut appartenir à aucun d'eux
- Pas d'état — sans annotation Spring, Java pur

```java
// REQUIS — Domain Service sans Spring
public class PricingService {

    public Money calculateTotal(Order order, Catalog catalog) {
        return order.getLines().stream()
            .map(line -> catalog.getPrice(line.getProductId()).multiply(line.getQuantity()))
            .reduce(Money.ZERO, Money::add);
    }
}
```

### Couche Application — règles

- Les **Application Services** orchestrent le domaine sans contenir de logique métier
- Ils chargent l'agrégat via le repository, appellent un comportement, sauvegardent, publient les events
- Chaque cas d'usage = une méthode ou un `CommandHandler` dédié
- `@Transactional` se pose ici, uniquement dans la couche application

```java
// REQUIS — Application Service : orchestration pure
@Service
@Transactional
public class ConfirmOrderService {

    private final OrderRepository orderRepository;
    private final DomainEventPublisher eventPublisher;

    public ConfirmOrderService(OrderRepository orderRepository,
                               DomainEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    public void confirm(ConfirmOrderCommand command) {
        Order order = orderRepository.findById(command.orderId())
            .orElseThrow(() -> new OrderNotFoundException(command.orderId()));

        order.confirm();                           // logique dans le domaine

        orderRepository.save(order);
        eventPublisher.publish(order.pullDomainEvents());  // events après persistence
    }
}

// REQUIS — Command : record immuable
public record ConfirmOrderCommand(OrderId orderId) {}
```

### Couche Infrastructure — règles

- Contient toute dépendance technique : JPA, messaging, HTTP clients
- Les entités JPA sont **séparées** des entités du domaine
- Un mapper explicite convertit entre entité JPA et objet du domaine dans chaque sens
- L'implémentation du repository implémente l'interface du domaine

```java
// REQUIS — Entité JPA dans infrastructure/persistence/entity (pas dans le domaine)
@Entity
@Table(name = "orders")
class OrderJpaEntity {
    @Id
    private UUID id;
    private UUID customerId;
    private String status;
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderLineJpaEntity> lines;
    // getters/setters JPA
}

// REQUIS — Implémentation du repository dans infrastructure
@Component
class OrderJpaRepository implements OrderRepository {

    private final OrderSpringDataRepository springDataRepository;
    private final OrderPersistenceMapper mapper;

    OrderJpaRepository(OrderSpringDataRepository springDataRepository,
                       OrderPersistenceMapper mapper) {
        this.springDataRepository = springDataRepository;
        this.mapper = mapper;
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        return springDataRepository.findById(id.value()).map(mapper::toDomain);
    }

    @Override
    public void save(Order order) {
        springDataRepository.save(mapper.toJpa(order));
    }
}
```

### Couche Présentation — règles

- Les `@RestController` ne connaissent que la couche application (commands, queries, services)
- Ils ne connaissent jamais directement le domaine (agrégats, value objects)
- Les DTOs de requête/réponse sont validés avec Bean Validation (`@Valid`, `@NotBlank`, etc.)
- Un mapper dédié convertit les DTOs en commands/queries et les résultats en DTOs de réponse
- Réponses HTTP avec `ResponseEntity<T>` et codes HTTP explicites

```java
// REQUIS — Contrôleur délégant via Command
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final ConfirmOrderService confirmOrderService;
    private final OrderQueryService orderQueryService;

    public OrderController(ConfirmOrderService confirmOrderService,
                           OrderQueryService orderQueryService) {
        this.confirmOrderService = confirmOrderService;
        this.orderQueryService = orderQueryService;
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable UUID id) {
        confirmOrderService.confirm(new ConfirmOrderCommand(OrderId.of(id)));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getById(@PathVariable UUID id) {
        return orderQueryService.findById(OrderId.of(id))
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}

// REQUIS — DTO de requête validé
public record CreateOrderRequest(
    @NotNull UUID customerId,
    @NotEmpty @Valid List<OrderLineRequest> lines
) {}
```

### Couche Présentation — documentation obligatoire des REST Controllers avec SpringDoc OpenAPI

**Tous les `@RestController` DOIVENT être documentés avec les annotations SpringDoc OpenAPI. Aucune exception.**

La version de SpringDoc OpenAPI à utiliser dépend du contexte Java/Spring Boot du projet — se référer à la matrice de compatibilité ci-dessous avant de configurer les dépendances. En l'absence de correspondance exacte, préférer la version antérieure (sécurité d'abord).

| Spring Boot | Java | SpringDoc OpenAPI | Endpoint Swagger |
|---|---|---|---|
| 2.7.x | 11-17 | 1.7.x (EOL) | `/v3/api-docs` |
| 3.0.x - 3.1.x | 17+ | 2.0.x - 2.8.x | `/v3/api-docs` |
| 3.2.x - 3.3.x | 17+ | 2.3.x - 2.8.x | `/v3/api-docs` |
| 4.0.x | 21+ | 3.0.x | `/v3/api-docs` |
| 4.1.x+ | 25+ | 3.0.x - 3.1.x+ | `/v3/api-docs` |

Configuration globale requise :
- Un fichier `OpenApiConfig.java` centralisé avec `@Configuration`, `@OpenAPIDefinition` (titre, version, description, contact, serveurs) et `@SecurityScheme(name = "bearerAuth", type = HTTP, scheme = "bearer", bearerFormat = "JWT")`
- Dépendance Maven `org.springdoc:springdoc-openapi-starter-webmvc-ui` (version compatible, voir matrice), et `<parameters>true</parameters>` dans `maven-compiler-plugin` (obligatoire depuis SB 3.2+)
- `application.yml` (dev) expose `springdoc.swagger-ui.path` et `springdoc.api-docs.path` ; le profil de **production désactive** `springdoc.swagger-ui.enabled` et `springdoc.api-docs.enabled`
- `SecurityConfig` : `permitAll` sur `GET /swagger-ui/**`, `GET /swagger-ui.html`, `GET /v3/api-docs/**`

Annotations obligatoires par controller :

| Annotation | Niveau | Obligatoire | Détail |
|---|---|---|---|
| `@Tag(name = "...")` | Classe | Oui | Groupe logique des endpoints |
| `@SecurityRequirement(name = "bearerAuth")` | Classe ou méthode | Si protégé | Marque les endpoints nécessitant un JWT Bearer token |
| `@Operation(summary = "...", description = "...")` | Méthode | Oui | Verbe HTTP, cas d'usage, données requises |
| `@ApiResponse(responseCode = "2xx", description = "...")` | Méthode | Oui | Tous les codes HTTP possibles (200, 201, 204, 400, 403, 404, 500) |
| `@ApiResponse(..., content = @Content(...))` | Méthode | Si réponse non-JSON | SSE, PDF, etc. |
| `@Parameter(hidden = true)` | Paramètre | Pour `@AuthenticationPrincipal` | Exclure du Swagger — ce n'est pas un input utilisateur |
| `@Parameter(description = "...", required = true)` | Paramètre | Pour inputs | Path, query, header, body |

```java
// REQUIS — controller REST documenté
@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Gestion des commandes")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirmer une commande", description = "Confirme une commande en brouillon")
    @ApiResponse(responseCode = "204", description = "Commande confirmée")
    @ApiResponse(responseCode = "404", description = "Commande non trouvée")
    public ResponseEntity<Void> confirm(
        @Parameter(description = "Identifiant de la commande", required = true)
        @PathVariable UUID id
    ) {
        confirmOrderService.confirm(new ConfirmOrderCommand(OrderId.of(id)));
        return ResponseEntity.noContent().build();
    }
}
```

Vérification obligatoire en revue de code — rejeter le code et exiger les annotations avant acceptation si :
- endpoint sans `@Operation`
- endpoint sans code HTTP réponse documenté (`@ApiResponse`)
- controller sans `@Tag`
- endpoint protégé sans `@SecurityRequirement`
- réponse non-JSON sans `@Content(mediaType = "...")`
- paramètre sans `@Parameter(description = "...")`
- `@AuthenticationPrincipal` visible dans Swagger (manque `@Parameter(hidden = true)`)

### Gestion des erreurs

- Les exceptions métier sont définies dans le domaine et héritent de `DomainException`
- Un `@ControllerAdvice` global les traduit en réponses HTTP structurées
- Format de réponse d'erreur uniforme sur tout le projet
- Logger avec SLF4J (`LoggerFactory.getLogger(...)`) — jamais `System.out.println`

```java
// REQUIS — Exception métier dans le domaine
public class DomainException extends RuntimeException {
    public DomainException(String message) { super(message); }
}
public class OrderNotFoundException extends DomainException {
    public OrderNotFoundException(OrderId id) {
        super("Commande introuvable : " + id.value());
    }
}

// REQUIS — Handler global
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(OrderNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse("NOT_FOUND", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomain(DomainException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(new ErrorResponse("DOMAIN_ERROR", ex.getMessage(), Instant.now()));
    }
}

public record ErrorResponse(String code, String message, Instant timestamp) {}
```

### Conventions de nommage Java / DDD

| Élément | Convention | Exemple |
|---|---|---|
| Agrégat / Entité | `PascalCase` | `Order`, `Customer` |
| Value Object | `PascalCase` | `OrderId`, `Email`, `Money` |
| Domain Event | `PascalCase` + `Event` | `OrderConfirmedEvent` |
| Command | `PascalCase` + `Command` | `ConfirmOrderCommand` |
| Query | `PascalCase` + `Query` | `FindOrderByIdQuery` |
| Application Service | `PascalCase` + `Service` | `ConfirmOrderService` |
| Repository interface | `PascalCase` + `Repository` | `OrderRepository` |
| DTO réponse | `PascalCase` + `Response` | `OrderResponse` |
| DTO requête | `PascalCase` + `Request` | `CreateOrderRequest` |
| Package bounded context | `lowercase` | `order`, `billing` |

### Bonnes pratiques Spring Boot / DDD

- Externaliser toute configuration dans `application.yml` — aucune valeur en dur dans le code
- Utiliser `@ConfigurationProperties` pour regrouper la configuration par bounded context
- Ne jamais mettre `@Transactional` dans le domaine ou l'infrastructure — uniquement dans l'application
- Tests unitaires sur le domaine (pur Java, sans Spring), tests d'intégration avec `@SpringBootTest` ou `@WebMvcTest`
- Ne jamais commiter de secrets — variables d'environnement ou vault obligatoires

---

## Règles communes aux deux stacks

- **Pas de code commenté** laissé dans le dépôt — supprimer ou ouvrir un ticket
- **Pas de `TODO` anonyme** — si un TODO est laissé, il doit avoir un ticket de suivi référencé
- Chaque PR doit être accompagnée de tests couvrant le comportement ajouté ou modifié
- Les commits suivent la convention `feat:`, `fix:`, `refactor:`, `test:`, `chore:`, `docs:`
- Signaler immédiatement toute dette technique introduite pour contrainte de délai

# Conventions de documentation SI

## Standards
- Documentation en français
- ADRs au format MADR (Markdown Any Decision Records)  
- Diagrammes en Mermaid
- Classification DICP pour les composants sensibles

## Patterns à détecter
- Architecture hexagonale (ports/adapters)
- DDD (Bounded Contexts, Aggregates, Domain Events)
- Spring Modulith (modules, Named Interfaces)
- OAuth2/OIDC (flows à documenter)

## Structure docs/ attendue
- README.md
- ARCHITECTURE.md  
- docs/adr/XXXX-titre.md
- docs/diagrams/