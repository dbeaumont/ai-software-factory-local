const form = document.querySelector('#ticket-form');
const submitButton = document.querySelector('#submit-button');
const message = document.querySelector('#form-message');
const emptyState = document.querySelector('#empty-state');
const taskStatus = document.querySelector('#task-status');
const statusPanel = document.querySelector('.status-panel');
const statusLabel = document.querySelector('#status-label');
const taskId = document.querySelector('#task-id');
const ticketKey = document.querySelector('#ticket-key');
const ticketTitle = document.querySelector('#ticket-title');
const taskSummary = document.querySelector('#task-summary');
const taskDetail = document.querySelector('#task-detail');
const progressBar = document.querySelector('#progress-bar');
const steps = document.querySelector('#steps');
const pipelineProgress = document.querySelector('#pipeline-progress');
const delegationGraph = document.querySelector('#delegation-graph');
const delegationTree = document.querySelector('#delegation-tree');
const delegationCount = document.querySelector('#delegation-count');
const evidencePanel = document.querySelector('#evidence-panel');
const evidenceList = document.querySelector('#evidence-list');
const evidenceCount = document.querySelector('#evidence-count');
const prLink = document.querySelector('#pr-link');
const approveButton = document.querySelector('#approve-button');
const effectConfirmation = document.querySelector('#effect-confirmation');
const effectTool = document.querySelector('#effect-tool');
const effectArguments = document.querySelector('#effect-arguments');
const effectImpact = document.querySelector('#effect-impact');
const effectPolicy = document.querySelector('#effect-policy');
const proposalButton = document.querySelector('#proposal-button');
const reviewButton = document.querySelector('#review-button');
const proposalDialog = document.querySelector('#proposal-dialog');
const proposalCloseButton = document.querySelector('#proposal-close-button');
const proposalPatch = document.querySelector('#proposal-patch');
const proposalPlan = document.querySelector('#proposal-plan');
const proposalTests = document.querySelector('#proposal-tests');
const proposalQuality = document.querySelector('#proposal-quality');
const proposalSecurity = document.querySelector('#proposal-security');
const proposalReview = document.querySelector('#proposal-review');
const reviewDialog = document.querySelector('#review-dialog');
const reviewCloseButton = document.querySelector('#review-close-button');
const reviewDecision = document.querySelector('#review-decision');
const reviewFindings = document.querySelector('#review-findings');
const reviewHumanPoints = document.querySelector('#review-human-points');
const reviewRaw = document.querySelector('#review-raw');
const llmDescription = document.querySelector('#llm-mode-description');
const cloudUnavailable = document.querySelector('#cloud-unavailable');
const taskLlmMode = document.querySelector('#task-llm-mode');
const advancedDetails = document.querySelector('.advanced-details');
const breadcrumbs = document.querySelector('#breadcrumbs');
const views = document.querySelectorAll('.app-view');
const viewLinks = document.querySelectorAll('[data-view]');
const executionList = document.querySelector('#execution-list');
const executionEmpty = document.querySelector('#execution-empty');
const refreshExecutionsButton = document.querySelector('#refresh-executions');
const debugFillButton = document.querySelector('#debug-fill-button');
const headerMenus = document.querySelectorAll('.topnav-menu, .tools-launcher');

const ticketTemplate = {
  summary: 'Ajouter GET /customers/{id}',
  businessGoal: "Ajouter un service permettant de récupérer les détails d'un customer.\nPour le moment, la page retournée ne doit afficher que l'id du customer.",
  scope: 'Customer API',
  currentBehavior: "Il n'existe pas encore de consultation d'un customer.",
  context: 'Utiliser CustomerController.java.',
  expectedBehavior: "1. Given : Quand un GET /customers/{id} est soumis, le système doit exécuter un service qui renvoie uniquement le customer id.\n2. When : quand l'API GET /customers/{id} est soumise, elle doit renvoyer l'id du customer en réponse.\n3. Done : la réponse doit être un flux JSON contenant l'id du customer.",
  acceptance: "- Cas nominal : la réponse JSON avec l'id du customer est affichée.\n- Cas d'erreur : lorsque l'id de customer demandé n'existe pas, une erreur 404 doit être retournée par l'API."
};

let activeTaskId;
let activeTask;
let pollTimer;
let executionsPollTimer;

headerMenus.forEach((menu) => {
  menu.addEventListener('mouseleave', () => {
    menu.open = false;
  });
});

const CAPABILITIES_RETRY_DELAY_MS = 3_000;
const MAX_CAPABILITIES_RETRIES = 2;

async function loadCapabilities(attempt = 0) {
  try {
    const response = await fetch('/api/capabilities');
    if (!response.ok) return;
    const capabilities = await response.json();
    if (!capabilities.cloudEnabled) {
      llmDescription.textContent = 'Le mode cloud est désactivé par la configuration de cette usine.';
      cloudUnavailable.textContent = 'Aucun moteur LLM n’est disponible.';
      cloudUnavailable.hidden = false;
      return;
    }
    if (capabilities.cloudAvailable) {
      cloudUnavailable.hidden = true;
      return;
    }

    if (!capabilities.cloudAvailable) {
      llmDescription.textContent = 'Le mode cloud est temporairement indisponible.';
      cloudUnavailable.textContent = capabilities.cloudError || 'L’API LLM externe est inaccessible.';
      cloudUnavailable.hidden = false;
      if (attempt < MAX_CAPABILITIES_RETRIES) {
        window.setTimeout(() => loadCapabilities(attempt + 1), CAPABILITIES_RETRY_DELAY_MS);
      }
    }
  } catch {
    cloudUnavailable.textContent = 'Impossible de vérifier la disponibilité du LLM cloud.';
    cloudUnavailable.hidden = false;
  }
}

async function readApiResponse(response) {
  const contentType = response.headers.get('content-type') || '';
  if (contentType.includes('application/json')) return response.json();

  const body = await response.text();
  if (!response.ok) {
    throw new Error(`Le service a répondu avec HTTP ${response.status}${body ? '.' : ''}`);
  }
  throw new Error('Le service a renvoyé une réponse inattendue.');
}

loadCapabilities();

function showView(name) {
  views.forEach((view) => { view.hidden = view.id !== `${name}-view`; });
  viewLinks.forEach((link) => link.classList.toggle('active', link.dataset.view === name));
  renderBreadcrumbs(name);
  if (name === 'executions') loadExecutions();
}

function renderBreadcrumbs(name) {
  if (name === 'executions') {
    breadcrumbs.innerHTML = '<a href="/">AI Software Factory</a><span>/</span><strong>Exécutions</strong>';
    document.title = 'AI Software Factory | Exécutions';
    return;
  }
  breadcrumbs.innerHTML = '<a href="/">AI Software Factory</a><span>/</span><strong>Tickets</strong>';
  document.title = 'AI Software Factory | Tickets';
}

function resetTicketDraft() {
  clearInterval(pollTimer);
  activeTaskId = undefined;
  activeTask = undefined;
  form.reset();
  delete form.dataset.taskId;
  message.textContent = '';
  submitButton.disabled = false;
  submitButton.innerHTML = 'Créer le ticket <span aria-hidden="true">→</span>';
  emptyState.hidden = false;
  taskStatus.hidden = true;
  statusPanel.classList.remove('failed');
  statusLabel.textContent = 'QUEUED';
  taskLlmMode.textContent = 'CLOUD';
  taskId.textContent = '';
  ticketKey.innerHTML = 'AF-NEW <span>·</span> DEMANDE DE LIVRAISON';
  ticketTitle.textContent = "Créer un ticket pour l'usine";
  taskSummary.textContent = '';
  taskDetail.textContent = '';
  progressBar.style.width = '8%';
  pipelineProgress.textContent = '0/9 opérations terminées';
  steps.replaceChildren();
  delegationGraph.hidden = true;
  delegationTree.replaceChildren();
  evidencePanel.hidden = true;
  evidenceList.replaceChildren();
  prLink.hidden = true;
  prLink.removeAttribute('href');
  approveButton.hidden = true;
  approveButton.disabled = false;
  approveButton.innerHTML = 'Approuver et créer la pull request <span aria-hidden="true">→</span>';
  proposalButton.hidden = true;
  reviewButton.hidden = true;
}

function requirementValue(requirement, startMarker, endMarkers) {
  const start = requirement.indexOf(startMarker);
  if (start < 0) return '';

  const contentStart = start + startMarker.length;
  const end = endMarkers
    .map((marker) => requirement.indexOf(marker, contentStart))
    .filter((index) => index >= 0)
    .reduce((closest, index) => Math.min(closest, index), requirement.length);
  return requirement.slice(contentStart, end).trim();
}

function restoreTicketFields(task) {
  if (!task?.id || form.dataset.taskId === task.id) return;

  const requirement = task.requirement || '';
  const values = {
    summary: requirementValue(requirement, 'Titre : ', ['\n']),
    businessGoal: requirementValue(requirement, 'Objectif métier :\n', ['\n\nContexte :\n']),
    scope: requirementValue(requirement, 'Contexte :\n- Application / domaine concerné : ', ['\n- Comportement actuel : ']),
    currentBehavior: requirementValue(requirement, '- Comportement actuel : ', ['\n\nComportement attendu :\n']),
    expectedBehavior: requirementValue(requirement, 'Comportement attendu :\n', ["\n\nCritères d'acceptation :\n"]),
    acceptance: requirementValue(requirement, "Critères d'acceptation :\n", [
      '\n\nContraintes existantes et fichiers pertinents :\n',
      '\n\nContraintes techniques :\n',
      '\n\nHors périmètre :\n',
      '\n\nValidation attendue :\n'
    ]),
    context: requirementValue(requirement, 'Contraintes existantes et fichiers pertinents :\n', [
      '\n\nContraintes techniques :\n', '\n\nHors périmètre :\n', '\n\nValidation attendue :\n'
    ]),
    technicalConstraints: requirementValue(requirement, 'Contraintes techniques :\n', ['\n\nHors périmètre :\n', '\n\nValidation attendue :\n']),
    outOfScope: requirementValue(requirement, 'Hors périmètre :\n', ['\n\nValidation attendue :\n']),
    validation: requirementValue(requirement, 'Validation attendue :\n', [])
  };

  Object.entries(values).forEach(([name, value]) => {
    const field = form.elements.namedItem(name);
    if (field) field.value = value;
  });
  form.elements.namedItem('repository').value = task.repositoryUrl || '';
  form.elements.namedItem('branch').value = task.baseBranch || 'main';
  advancedDetails.open = Boolean(values.technicalConstraints || values.outOfScope || values.validation);
  form.dataset.taskId = task.id;
}

debugFillButton.addEventListener('click', () => {
  Object.entries(ticketTemplate).forEach(([name, value]) => {
    form.elements.namedItem(name).value = value;
  });
  message.textContent = 'Modèle de ticket chargé. Vérifiez les valeurs avant envoi.';
  document.querySelector('#summary').focus();
});

viewLinks.forEach((link) => link.addEventListener('click', (event) => {
  event.preventDefault();
  if (link.dataset.view === 'ticket') resetTicketDraft();
  showView(link.dataset.view);
  history.replaceState(null, '', `#${link.dataset.view}`);
}));

refreshExecutionsButton.addEventListener('click', loadExecutions);

const progress = {
  QUEUED: 8, CLONING: 18, PLANNING: 32, GENERATING_PATCH: 47,
  APPLYING_PATCH: 60, TESTING: 70, QUALITY_SCANNING: 78, SECURITY_SCANNING: 86,
  REVIEWING: 94, WAITING_APPROVAL: 100, APPROVED: 100,
  PR_CREATED: 100, FAILED: 100
};

const pipelineStages = [
  { name: 'Préparation', jobs: [{ id: 'CLONING', label: 'Clonage du dépôt' }] },
  { name: 'Conception', jobs: [{ id: 'PLANNING', label: 'Planning' }] },
  { name: 'Développement', jobs: [{ id: 'GENERATING_PATCH', label: 'Génération du patch' }, { id: 'APPLYING_PATCH', label: 'Application du patch' }] },
  { name: 'Validation', jobs: [{ id: 'TESTING', label: 'Build + tests via Artifactory' }, { id: 'QUALITY_SCANNING', label: 'Analyse SonarQube' }, { id: 'SECURITY_SCANNING', label: 'Analyse sécurité' }] },
  { name: 'Revue', jobs: [{ id: 'REVIEWING', label: 'Revue IA' }] },
  { name: 'Livraison', jobs: [{ id: 'WAITING_APPROVAL', label: 'Approbation humaine' }, { id: 'PR_CREATED', label: 'Création de la pull request' }] }
];
const pipelineJobs = pipelineStages.flatMap((stage) => stage.jobs);

function buildRequirement(data) {
  const sections = [
    `Titre : ${data.summary.trim()}`,
    `Objectif métier :\n${data.businessGoal.trim()}`,
    `Contexte :\n- Application / domaine concerné : ${data.scope.trim()}\n- Comportement actuel : ${data.currentBehavior.trim()}`,
    `Comportement attendu :\n${data.expectedBehavior.trim()}`,
    `Critères d'acceptation :\n${data.acceptance.trim()}`
  ];
  if (data.context.trim()) sections.push(`Contraintes existantes et fichiers pertinents :\n${data.context.trim()}`);
  if (data.technicalConstraints.trim()) sections.push(`Contraintes techniques :\n${data.technicalConstraints.trim()}`);
  if (data.outOfScope.trim()) sections.push(`Hors périmètre :\n${data.outOfScope.trim()}`);
  if (data.validation.trim()) sections.push(`Validation attendue :\n${data.validation.trim()}`);
  return sections.join('\n\n');
}

function taskTitle(task) {
  return task.requirement.match(/^Titre : (.*)$/m)?.[1] || 'Ticket sans titre';
}

function displayTicketNumber(task) {
  return task.ticketNumber || `#${task.id.slice(0, 8)}`;
}

function activeStep(task) {
  if (task.status === 'APPROVED') return 'Création de la pull request';
  return pipelineJobs.find((job) => job.id === task.status)?.label || task.status.replaceAll('_', ' ');
}

function displayDate(value) {
  return value ? new Intl.DateTimeFormat('fr-FR', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)) : '—';
}

function stateClass(status) {
  if (status === 'FAILED') return 'failed';
  if (['WAITING_APPROVAL', 'PR_CREATED'].includes(status)) return 'pending';
  return '';
}

function renderExecutionList(tasks) {
  const sorted = [...tasks].sort((left, right) => new Date(right.updatedAt) - new Date(left.updatedAt));
  executionList.replaceChildren(...sorted.map((task) => {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'execution-row';
    const title = document.createElement('span');
    title.innerHTML = `<span class="execution-title"></span><span class="execution-key"></span>`;
    title.querySelector('.execution-title').textContent = taskTitle(task);
    title.querySelector('.execution-key').textContent = displayTicketNumber(task);
    const mode = document.createElement('span');
    mode.className = `execution-mode ${(task.llmMode || 'CLOUD').toLowerCase()}`;
    mode.textContent = task.llmMode || 'CLOUD';
    const step = document.createElement('span');
    step.className = 'execution-step';
    step.textContent = activeStep(task);
    const date = document.createElement('span');
    date.className = 'execution-date';
    date.textContent = displayDate(task.updatedAt);
    const state = document.createElement('span');
    state.className = `execution-state ${stateClass(task.status)}`;
    state.textContent = task.status.replaceAll('_', ' ');
    row.append(title, mode, step, date, state);
    row.addEventListener('click', () => {
      activeTaskId = task.id;
      renderTask(task);
      showView('ticket');
      clearInterval(pollTimer);
      if (!isFinished(task.status)) pollTimer = setInterval(refreshTask, 3000);
    });
    return row;
  }));
  executionEmpty.hidden = sorted.length !== 0;
}

async function loadExecutions() {
  try {
    const response = await fetch('/api/tasks');
    if (!response.ok) throw new Error('Impossible de charger les exécutions.');
    renderExecutionList(await response.json());
  } catch (error) {
    executionList.replaceChildren();
    executionEmpty.textContent = error.message;
    executionEmpty.hidden = false;
  }
}

function browserPullRequestUrl(value) {
  const url = new URL(value, window.location.origin);
  if (url.hostname === 'gitea') {
    url.protocol = window.location.protocol;
    url.hostname = window.location.hostname;
    url.port = '3000';
  }
  return url.toString();
}

function isFinished(status) {
  return ['WAITING_APPROVAL', 'PR_CREATED', 'FAILED'].includes(status);
}

function jobState(task, job, knownSteps) {
  if (job.id === task.status) return 'running';
  const actual = knownSteps.get(job.id);
  if (actual) return 'done';
  if (job.id === 'WAITING_APPROVAL' && ['APPROVED', 'PR_CREATED'].includes(task.status)) return 'done';
  if (task.status === 'FAILED') return 'pending';
  const currentIndex = pipelineJobs.findIndex((item) => item.id === task.status);
  const jobIndex = pipelineJobs.findIndex((item) => item.id === job.id);
  return currentIndex > jobIndex ? 'done' : 'pending';
}

function renderPipeline(task) {
  const knownSteps = new Map((task.steps || []).map((step) => [step.name, step]));
  let completed = 0;
  const stages = pipelineStages.map((stage) => {
    const section = document.createElement('section');
    section.className = 'pipeline-stage';
    const title = document.createElement('h3');
    title.textContent = stage.name;
    const jobs = document.createElement('ol');
    jobs.className = 'pipeline-jobs';
    stage.jobs.forEach((job) => {
      const state = jobState(task, job, knownSteps);
      if (state === 'done' || state === 'skipped') completed += 1;
      const item = document.createElement('li');
      item.className = `pipeline-job ${state}`;
      const marker = document.createElement('span');
      marker.className = 'job-marker';
      marker.textContent = state === 'done' ? '✓' : state === 'skipped' ? '–' : state === 'running' ? '●' : '○';
      const content = document.createElement('span');
      content.className = 'job-content';
      const label = document.createElement('strong');
      label.textContent = job.label;
      content.append(label);
      const actual = knownSteps.get(job.id);
      if (state === 'running' && actual?.summary) {
        const detail = document.createElement('small');
        detail.textContent = actual.summary;
        content.append(detail);
      }
      item.append(marker, content);
      jobs.append(item);
    });
    section.append(title, jobs);
    return section;
  });
  steps.replaceChildren(...stages);
  pipelineProgress.textContent = `${completed}/${pipelineJobs.length} opérations terminées`;
}

function renderDelegationDag(task) {
  const delegations = Array.isArray(task.delegations) ? task.delegations : [];
  delegationTree.replaceChildren();
  delegationGraph.hidden = delegations.length === 0;
  if (delegations.length === 0) return;

  const nodeIds = new Set(delegations.map((node) => node.delegationId));
  const children = new Map();
  delegations.forEach((node) => {
    const parent = node.parentDelegationId && nodeIds.has(node.parentDelegationId)
      ? node.parentDelegationId : '__root__';
    if (!children.has(parent)) children.set(parent, []);
    children.get(parent).push(node);
  });
  children.forEach((nodes) => nodes.sort((left, right) => left.delegationId.localeCompare(right.delegationId)));

  const visited = new Set();
  const renderBranch = (node) => {
    const perimeter = delegationPerimeter(node.role);
    const branch = document.createElement('article');
    branch.className = `delegation-node perimeter-${perimeter.id} status-${String(node.status || 'unknown').toLowerCase()}`;
    branch.dataset.delegationId = node.delegationId;
    branch.dataset.perimeter = perimeter.id;
    const header = document.createElement('div');
    header.className = 'delegation-node-header';
    const identity = document.createElement('span');
    identity.className = 'delegation-identity';
    const role = document.createElement('strong');
    role.textContent = node.role || 'agent';
    const id = document.createElement('small');
    id.textContent = node.delegationId;
    identity.append(role, id);
    const status = document.createElement('span');
    status.className = 'delegation-status';
    status.textContent = node.status || 'UNKNOWN';
    const badges = document.createElement('span');
    badges.className = 'delegation-badges';
    const perimeterBadge = document.createElement('span');
    perimeterBadge.className = 'delegation-perimeter';
    perimeterBadge.textContent = perimeter.label;
    badges.append(perimeterBadge, status);
    header.append(identity, badges);
    branch.append(header);

    if (Array.isArray(node.dependsOn) && node.dependsOn.length > 0) {
      const dependencies = document.createElement('small');
      dependencies.className = 'delegation-dependencies';
      dependencies.textContent = `Dépend de : ${node.dependsOn.join(', ')}`;
      branch.append(dependencies);
    }
    if (node.stopReason) {
      const reason = document.createElement('small');
      reason.className = 'delegation-stop-reason';
      reason.textContent = node.stopReason;
      branch.append(reason);
    }
    const metrics = document.createElement('dl');
    metrics.className = 'delegation-metrics';
    [
      ['Durée', formatDuration(node.durationMillis)],
      ['Tours', Number(node.turns || 0).toLocaleString('fr-FR')],
      ['Tokens', Number(node.tokens || 0).toLocaleString('fr-FR')],
      ['Coût', `${(Number(node.costMicros || 0) / 1_000_000).toFixed(4)}`]
    ].forEach(([label, value]) => {
      const term = document.createElement('dt');
      term.textContent = label;
      const detail = document.createElement('dd');
      detail.textContent = value;
      metrics.append(term, detail);
    });
    branch.append(metrics);
    if (Array.isArray(node.toolsUsed) && node.toolsUsed.length > 0) {
      const tools = document.createElement('small');
      tools.className = 'delegation-tools';
      tools.textContent = `Outils : ${node.toolsUsed.join(', ')}`;
      branch.append(tools);
    }
    if (node.codeImpact) {
      const impact = document.createElement('section');
      impact.className = 'delegation-code-impact';
      const impactTitle = document.createElement('strong');
      impactTitle.textContent = 'Impact Code';
      impact.append(impactTitle);
      [
        ['Scopes', node.codeImpact.scopes],
        ['Fichiers', node.codeImpact.touchedFiles],
        ['Collisions', node.codeImpact.collisions]
      ].forEach(([label, values]) => {
        const line = document.createElement('p');
        const safeValues = Array.isArray(values) ? values : [];
        line.className = label === 'Collisions' && safeValues.length > 0 ? 'has-collisions' : '';
        line.textContent = `${label} : ${safeValues.length > 0 ? safeValues.join(', ') : 'aucun'}`;
        impact.append(line);
      });
      branch.append(impact);
    }
    visited.add(node.delegationId);
    const descendants = (children.get(node.delegationId) || []).filter((child) => !visited.has(child.delegationId));
    if (descendants.length > 0) {
      const childContainer = document.createElement('div');
      childContainer.className = 'delegation-children';
      childContainer.append(...descendants.map(renderBranch));
      branch.append(childContainer);
    }
    return branch;
  };

  const roots = children.get('__root__') || [];
  delegationTree.append(...roots.map(renderBranch));
  delegationTree.append(...delegations.filter((node) => !visited.has(node.delegationId)).map(renderBranch));
  delegationCount.textContent = `${delegations.length} délégation${delegations.length > 1 ? 's' : ''}`;
}

function formatDuration(durationMillis) {
  const millis = Number(durationMillis || 0);
  if (millis < 1_000) return `${millis} ms`;
  if (millis < 60_000) return `${(millis / 1_000).toFixed(1)} s`;
  return `${Math.floor(millis / 60_000)} min ${Math.floor((millis % 60_000) / 1_000)} s`;
}

function delegationPerimeter(role) {
  const normalized = String(role || '').toLowerCase();
  if (normalized.includes('independent') || normalized === 'reviewer') return { id: 'review', label: 'Revue indépendante' };
  if (normalized.includes('security') || normalized.includes('threat')) return { id: 'security', label: 'Sécurité' };
  if (normalized.includes('test') || normalized.includes('quality')) return { id: 'tests', label: 'Tests' };
  if (normalized.includes('architect') || normalized.includes('impact') || normalized.includes('contract')) {
    return { id: 'architecture', label: 'Architecture' };
  }
  return { id: 'code', label: 'Code' };
}

function renderEvidence(task) {
  const artifacts = Array.isArray(task.artifacts) ? task.artifacts : [];
  evidenceList.replaceChildren();
  evidencePanel.hidden = artifacts.length === 0;
  if (artifacts.length === 0) return;

  evidenceList.append(...artifacts.map((artifact) => {
    const item = document.createElement('article');
    item.className = `evidence-item status-${String(artifact.status || 'unknown').toLowerCase()}`;
    const header = document.createElement('div');
    header.className = 'evidence-header';
    const type = document.createElement('strong');
    type.textContent = artifact.type || 'PREUVE';
    const status = document.createElement('span');
    status.textContent = artifact.status || 'UNKNOWN';
    header.append(type, status);
    const metadata = document.createElement('p');
    metadata.textContent = `${artifact.classification || 'NON CLASSIFIÉ'} · ${formatBytes(artifact.sizeBytes)}`;
    const digest = document.createElement('code');
    digest.textContent = `SHA-256 ${artifact.digest || 'indisponible'}`;
    const uri = document.createElement('small');
    uri.textContent = artifact.uri ? `URI autorisée : ${artifact.uri}` : 'URI masquée par la politique d’accès';
    item.append(header, metadata, digest, uri);
    return item;
  }));
  evidenceCount.textContent = `${artifacts.length} artefact${artifacts.length > 1 ? 's' : ''}`;
}

function formatBytes(value) {
  const bytes = Number(value || 0);
  if (bytes < 1_024) return `${bytes} o`;
  if (bytes < 1_048_576) return `${(bytes / 1_024).toFixed(1)} Kio`;
  return `${(bytes / 1_048_576).toFixed(1)} Mio`;
}

function renderTask(task) {
  activeTask = task;
  restoreTicketFields(task);
  emptyState.hidden = true;
  taskStatus.hidden = false;
  statusPanel.classList.toggle('failed', task.status === 'FAILED');
  statusLabel.textContent = task.status.replaceAll('_', ' ');
  taskLlmMode.textContent = task.llmMode || 'CLOUD';
  taskId.textContent = displayTicketNumber(task);
  ticketKey.textContent = `Ticket ${displayTicketNumber(task)}`;
  ticketTitle.textContent = taskTitle(task);
  taskSummary.textContent = taskTitle(task);
  taskDetail.textContent = task.error || statusDescription(task.status);
  progressBar.style.width = `${progress[task.status] || 10}%`;
  renderPipeline(task);
  renderDelegationDag(task);
  renderEvidence(task);
  proposalButton.hidden = !task.patch || task.status === 'PR_CREATED';
  reviewButton.hidden = !task.review;
  approveButton.hidden = task.status !== 'WAITING_APPROVAL';
  renderPendingEffect(task.pendingEffect, task.status === 'WAITING_APPROVAL');
  prLink.hidden = !task.pullRequestUrl;
  if (task.pullRequestUrl) prLink.href = browserPullRequestUrl(task.pullRequestUrl);
}

function renderPendingEffect(effect, visible) {
  effectConfirmation.hidden = !visible || !effect;
  effectArguments.replaceChildren();
  if (!visible || !effect) return;
  effectTool.textContent = effect.tool || 'Outil non précisé';
  Object.entries(effect.safeArguments || {}).forEach(([name, value]) => {
    const term = document.createElement('dt');
    term.textContent = name;
    const detail = document.createElement('dd');
    detail.textContent = String(value);
    effectArguments.append(term, detail);
  });
  effectImpact.textContent = `Impact : ${effect.impact || 'non précisé'}`;
  effectPolicy.textContent = `Policy gate : ${effect.policyDecision || 'INDETERMINATE'}`;
}

proposalButton.addEventListener('click', () => {
  if (!activeTask) return;
  proposalPatch.textContent = activeTask.patch || 'Aucun diff généré.';
  proposalPlan.textContent = activeTask.plan || 'Aucun plan généré.';
  proposalTests.textContent = assuranceText(activeTask.assuranceResults?.tests, activeTask.testSummary, 'Aucun résultat de test disponible.');
  proposalQuality.textContent = assuranceText(activeTask.assuranceResults?.quality, activeTask.qualitySummary, 'Aucun résultat SonarQube disponible.');
  proposalSecurity.textContent = assuranceText(activeTask.assuranceResults?.security, activeTask.securitySummary, 'Aucun résultat de sécurité disponible.');
  proposalReview.textContent = activeTask.review || 'Aucune revue IA disponible.';
  if (typeof proposalDialog.showModal === 'function') proposalDialog.showModal();
  else proposalDialog.open = true;
});

proposalCloseButton.addEventListener('click', () => proposalDialog.close());

function reviewValue(value, fallback) {
  return typeof value === 'string' && value.trim() ? value.trim() : fallback;
}

function assuranceText(result, raw, fallback) {
  if (!result) return raw || fallback;
  const evidence = result.evidence || {};
  const verdict = result.verdict || result.status || 'INDETERMINATE';
  const digest = evidence.digest || result.digest || 'digest indisponible';
  const uri = evidence.uri || result.uri || 'URI indisponible';
  return `Verdict : ${verdict}\nPreuve : ${uri}\nSHA-256 : ${digest}\n\n${raw || ''}`.trim();
}

function reviewList(values, emptyMessage) {
  if (!Array.isArray(values) || values.length === 0) {
    const item = document.createElement('li');
    item.textContent = emptyMessage;
    return [item];
  }
  return values.map((value) => {
    const item = document.createElement('li');
    item.textContent = reviewValue(value, 'Information non précisée.');
    return item;
  });
}

function parseReviewerReport(review) {
  const value = (review || '').trim();
  if (!value.startsWith('```')) return JSON.parse(value);
  const firstLineEnd = value.indexOf('\n');
  if (firstLineEnd < 0) return JSON.parse(value);
  const json = value.slice(firstLineEnd + 1).replace(/\n?```\s*$/, '').trim();
  return JSON.parse(json);
}

function renderReviewDetails(review) {
  reviewRaw.textContent = review || 'Aucune revue IA disponible.';
  reviewFindings.replaceChildren();
  reviewHumanPoints.replaceChildren();

  try {
    const report = parseReviewerReport(review);
    const decision = reviewValue(report.decision, 'DÉCISION NON PRÉCISÉE');
    reviewDecision.textContent = `Décision du reviewer : ${decision}`;
    reviewDecision.className = `review-decision ${decision.toLowerCase()}`;

    const findings = Array.isArray(report.findings) ? report.findings : [];
    if (findings.length === 0) {
      const empty = document.createElement('p');
      empty.textContent = 'Aucun constat bloquant ou commentaire n’a été remonté.';
      reviewFindings.append(empty);
    } else {
      findings.forEach((finding, index) => {
        const details = finding && typeof finding === 'object' ? finding : {};
        const item = document.createElement('article');
        const severity = reviewValue(details.severity, 'non précisée');
        const severityClass = severity.toLowerCase().replace(/[^a-z0-9-]/g, '-');
        item.className = `review-finding severity-${severityClass}`;
        const title = document.createElement('h4');
        title.textContent = `${index + 1}. ${severity.toUpperCase()} — ${reviewValue(details.file, 'Fichier non précisé')}`;
        const rule = document.createElement('p');
        rule.textContent = `Motif : ${reviewValue(details.rule, 'Règle non précisée.')}`;
        const fix = document.createElement('p');
        fix.textContent = `Correctif recommandé : ${reviewValue(details.fix, 'Aucun correctif précisé.')}`;
        item.append(title, rule, fix);
        if (Array.isArray(details.evidence) && details.evidence.length > 0) {
          const evidenceTitle = document.createElement('h5');
          evidenceTitle.textContent = 'Éléments examinés';
          const evidence = document.createElement('ul');
          evidence.append(...reviewList(details.evidence, 'Aucun élément fourni.'));
          item.append(evidenceTitle, evidence);
        }
        reviewFindings.append(item);
      });
    }
    reviewHumanPoints.append(...reviewList(report.human_review_points, 'Aucun point de revue humaine signalé.'));
  } catch {
    reviewDecision.textContent = 'La réponse du reviewer ne respecte pas le format structuré attendu.';
    reviewDecision.className = 'review-decision invalid';
    const explanation = document.createElement('p');
    explanation.textContent = 'Consultez la réponse brute ci-dessous pour le détail disponible.';
    reviewFindings.append(explanation);
    reviewHumanPoints.append(...reviewList([], 'Aucun point de revue humaine structuré.'));
  }
}

reviewButton.addEventListener('click', () => {
  if (!activeTask?.review) return;
  renderReviewDetails(activeTask.review);
  if (typeof reviewDialog.showModal === 'function') reviewDialog.showModal();
  else reviewDialog.open = true;
});

reviewCloseButton.addEventListener('click', () => reviewDialog.close());

function statusDescription(status) {
  if (status === 'WAITING_APPROVAL') return 'Contrôles terminés. La tâche attend une approbation humaine.';
  if (status === 'PR_CREATED') return 'La pull request a été créée dans Gitea.';
  if (status === 'FAILED') return 'L’exécution s’est arrêtée. Consultez l’erreur remontée par l’usine.';
  return 'L’usine traite votre ticket. Cette vue se met à jour automatiquement.';
}

async function refreshTask() {
  if (!activeTaskId) return;
  try {
    const response = await fetch(`/api/tasks/${activeTaskId}`);
    if (!response.ok) throw new Error('Impossible de suivre cette tâche.');
    const task = await readApiResponse(response);
    renderTask(task);
    loadExecutions();
    if (isFinished(task.status)) clearInterval(pollTimer);
  } catch (error) {
    taskDetail.textContent = error.message;
    clearInterval(pollTimer);
  }
}

approveButton.addEventListener('click', async () => {
  if (!activeTaskId) return;
  approveButton.disabled = true;
  approveButton.textContent = 'Approbation en cours...';
  try {
    const response = await fetch(`/api/tasks/${activeTaskId}/approve`, { method: 'POST' });
    const task = await readApiResponse(response);
    if (!response.ok) throw new Error(task.error || "L'approbation a échoué.");
    renderTask(task);
    clearInterval(pollTimer);
    pollTimer = setInterval(refreshTask, 3000);
  } catch (error) {
    taskDetail.textContent = error.message;
    approveButton.disabled = false;
    approveButton.innerHTML = 'Réessayer l’approbation <span aria-hidden="true">→</span>';
  }
});

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(form));
  message.textContent = '';
  submitButton.disabled = true;
  submitButton.textContent = 'Transmission...';
  try {
    const response = await fetch('/api/tasks', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        repositoryUrl: data.repository,
        baseBranch: data.branch,
        requirement: buildRequirement(data),
        llmMode: 'CLOUD'
      })
    });
    const task = await readApiResponse(response);
    if (!response.ok) throw new Error(task.error || 'La création du ticket a échoué.');
    activeTaskId = task.id;
    renderTask(task);
    clearInterval(pollTimer);
    pollTimer = setInterval(refreshTask, 3000);
    message.textContent = `Ticket ${displayTicketNumber(task)} envoyé.`;
    loadExecutions();
  } catch (error) {
    message.textContent = error.message;
  } finally {
    submitButton.disabled = false;
    submitButton.innerHTML = 'Envoyer à l\'usine <span aria-hidden="true">→</span>';
  }
});

executionsPollTimer = setInterval(loadExecutions, 5000);
showView(window.location.hash === '#executions' ? 'executions' : 'ticket');
