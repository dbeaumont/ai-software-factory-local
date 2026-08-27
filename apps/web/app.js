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
const prLink = document.querySelector('#pr-link');
const approveButton = document.querySelector('#approve-button');
const proposalButton = document.querySelector('#proposal-button');
const proposalDialog = document.querySelector('#proposal-dialog');
const proposalCloseButton = document.querySelector('#proposal-close-button');
const proposalPatch = document.querySelector('#proposal-patch');
const proposalPlan = document.querySelector('#proposal-plan');
const proposalTests = document.querySelector('#proposal-tests');
const proposalQuality = document.querySelector('#proposal-quality');
const proposalSecurity = document.querySelector('#proposal-security');
const proposalReview = document.querySelector('#proposal-review');
const llmMode = document.querySelector('#llm-mode');
const llmDescription = document.querySelector('#llm-mode-description');
const cloudWarning = document.querySelector('#cloud-warning');
const cloudUnavailable = document.querySelector('#cloud-unavailable');
const taskLlmMode = document.querySelector('#task-llm-mode');
const llmChoice = document.querySelector('.llm-choice');
const breadcrumbs = document.querySelector('#breadcrumbs');
const views = document.querySelectorAll('.app-view');
const viewLinks = document.querySelectorAll('[data-view]');
const executionList = document.querySelector('#execution-list');
const executionEmpty = document.querySelector('#execution-empty');
const refreshExecutionsButton = document.querySelector('#refresh-executions');
const debugFillButton = document.querySelector('#debug-fill-button');

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

function renderLlmMode() {
  const cloud = llmMode.checked;
  llmChoice.classList.toggle('cloud-selected', cloud);
  llmDescription.textContent = cloud
    ? "Le ticket sera traité par le modèle cloud configuré dans LiteLLM."
    : "Le code et la spécification restent dans l'environnement Docker local via Ollama.";
  cloudWarning.hidden = !cloud;
}

llmMode.addEventListener('change', renderLlmMode);

async function loadCapabilities() {
  try {
    const response = await fetch('/api/capabilities');
    if (!response.ok) return;
    const capabilities = await response.json();
    if (!capabilities.cloudEnabled) {
      llmMode.disabled = true;
      llmDescription.textContent = 'Le mode cloud est désactivé par la configuration de cette usine.';
      return;
    }
    if (!capabilities.cloudAvailable) {
      llmMode.checked = false;
      llmMode.disabled = true;
      llmDescription.textContent = 'Le mode cloud est temporairement indisponible.';
      cloudUnavailable.textContent = capabilities.cloudError || 'L’API LLM externe est inaccessible.';
      cloudUnavailable.hidden = false;
      renderLlmMode();
    }
  } catch {
    // The local mode remains available when the status endpoint is temporarily unavailable.
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
    document.title = 'AI Factory | Exécutions';
    return;
  }
  breadcrumbs.innerHTML = '<a href="/">AI Software Factory</a><span>/</span><strong>Tickets</strong>';
  document.title = 'AI Factory | Tickets';
}

function resetTicketDraft() {
  clearInterval(pollTimer);
  activeTaskId = undefined;
  activeTask = undefined;
  form.reset();
  renderLlmMode();
  message.textContent = '';
  submitButton.disabled = false;
  submitButton.innerHTML = 'Créer le ticket <span aria-hidden="true">→</span>';
  emptyState.hidden = false;
  taskStatus.hidden = true;
  statusPanel.classList.remove('failed');
  statusLabel.textContent = 'QUEUED';
  taskLlmMode.textContent = 'LOCAL';
  taskId.textContent = '';
  ticketKey.innerHTML = 'AF-NEW <span>·</span> DEMANDE DE LIVRAISON';
  ticketTitle.textContent = "Créer un ticket pour l'usine";
  taskSummary.textContent = '';
  taskDetail.textContent = '';
  progressBar.style.width = '8%';
  pipelineProgress.textContent = '0/9 opérations terminées';
  steps.replaceChildren();
  prLink.hidden = true;
  prLink.removeAttribute('href');
  approveButton.hidden = true;
  approveButton.disabled = false;
  approveButton.innerHTML = 'Approuver et créer la pull request <span aria-hidden="true">→</span>';
  proposalButton.hidden = true;
}

debugFillButton.addEventListener('click', () => {
  Object.entries(ticketTemplate).forEach(([name, value]) => {
    form.elements.namedItem(name).value = value;
  });
  llmMode.checked = false;
  renderLlmMode();
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
    mode.className = `execution-mode ${(task.llmMode || 'LOCAL').toLowerCase()}`;
    mode.textContent = task.llmMode || 'LOCAL';
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

function renderTask(task) {
  activeTask = task;
  emptyState.hidden = true;
  taskStatus.hidden = false;
  statusPanel.classList.toggle('failed', task.status === 'FAILED');
  statusLabel.textContent = task.status.replaceAll('_', ' ');
  taskLlmMode.textContent = task.llmMode || 'LOCAL';
  taskId.textContent = displayTicketNumber(task);
  ticketKey.textContent = `Ticket ${displayTicketNumber(task)}`;
  ticketTitle.textContent = taskTitle(task);
  taskSummary.textContent = taskTitle(task);
  taskDetail.textContent = task.error || statusDescription(task.status);
  progressBar.style.width = `${progress[task.status] || 10}%`;
  renderPipeline(task);
  proposalButton.hidden = !task.patch || task.status === 'PR_CREATED';
  approveButton.hidden = task.status !== 'WAITING_APPROVAL';
  prLink.hidden = !task.pullRequestUrl;
  if (task.pullRequestUrl) prLink.href = browserPullRequestUrl(task.pullRequestUrl);
}

proposalButton.addEventListener('click', () => {
  if (!activeTask) return;
  proposalPatch.textContent = activeTask.patch || 'Aucun diff généré.';
  proposalPlan.textContent = activeTask.plan || 'Aucun plan généré.';
  proposalTests.textContent = activeTask.testSummary || 'Aucun résultat de test disponible.';
  proposalQuality.textContent = activeTask.qualitySummary || 'Aucun résultat SonarQube disponible.';
  proposalSecurity.textContent = activeTask.securitySummary || 'Aucun résultat de sécurité disponible.';
  proposalReview.textContent = activeTask.review || 'Aucune revue IA disponible.';
  if (typeof proposalDialog.showModal === 'function') proposalDialog.showModal();
  else proposalDialog.open = true;
});

proposalCloseButton.addEventListener('click', () => proposalDialog.close());

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
  const selectedLlmMode = llmMode.checked ? 'CLOUD' : 'LOCAL';
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
        llmMode: selectedLlmMode
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
