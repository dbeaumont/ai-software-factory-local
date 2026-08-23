const form = document.querySelector('#ticket-form');
const submitButton = document.querySelector('#submit-button');
const message = document.querySelector('#form-message');
const emptyState = document.querySelector('#empty-state');
const taskStatus = document.querySelector('#task-status');
const statusPanel = document.querySelector('.status-panel');
const statusLabel = document.querySelector('#status-label');
const taskId = document.querySelector('#task-id');
const taskSummary = document.querySelector('#task-summary');
const taskDetail = document.querySelector('#task-detail');
const progressBar = document.querySelector('#progress-bar');
const steps = document.querySelector('#steps');
const prLink = document.querySelector('#pr-link');

let activeTaskId;
let pollTimer;

const progress = {
  QUEUED: 8, CLONING: 18, PLANNING: 32, GENERATING_PATCH: 47,
  APPLYING_PATCH: 60, TESTING: 72, SECURITY_SCANNING: 82,
  REVIEWING: 92, WAITING_APPROVAL: 100, APPROVED: 100,
  PR_CREATED: 100, FAILED: 100
};

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

function isFinished(status) {
  return ['WAITING_APPROVAL', 'PR_CREATED', 'FAILED'].includes(status);
}

function renderTask(task) {
  emptyState.hidden = true;
  taskStatus.hidden = false;
  statusPanel.classList.toggle('failed', task.status === 'FAILED');
  statusLabel.textContent = task.status.replaceAll('_', ' ');
  taskId.textContent = `#${task.id.slice(0, 8)}`;
  taskSummary.textContent = task.requirement.match(/^Titre : (.*)$/m)?.[1] || 'Ticket soumis';
  taskDetail.textContent = task.error || statusDescription(task.status, task.dryRun);
  progressBar.style.width = `${progress[task.status] || 10}%`;
  steps.replaceChildren(...(task.steps || []).slice(-6).map((step) => {
    const item = document.createElement('li');
    const name = document.createElement('span');
    const state = document.createElement('strong');
    name.textContent = step.name || step.phase || 'Étape';
    state.textContent = step.status || 'EN COURS';
    item.append(name, state);
    return item;
  }));
  prLink.hidden = !task.pullRequestUrl;
  if (task.pullRequestUrl) prLink.href = task.pullRequestUrl;
}

function statusDescription(status, dryRun) {
  if (status === 'WAITING_APPROVAL') return dryRun ? 'Simulation terminée. Le patch et la revue sont prêts à être consultés.' : 'Contrôles terminés. La tâche attend une approbation humaine.';
  if (status === 'PR_CREATED') return 'La pull request a été créée dans Gitea.';
  if (status === 'FAILED') return 'L’exécution s’est arrêtée. Consultez l’erreur remontée par l’usine.';
  return 'L’usine traite votre ticket. Cette vue se met à jour automatiquement.';
}

async function refreshTask() {
  if (!activeTaskId) return;
  try {
    const response = await fetch(`/api/tasks/${activeTaskId}`);
    if (!response.ok) throw new Error('Impossible de suivre cette tâche.');
    const task = await response.json();
    renderTask(task);
    if (isFinished(task.status)) clearInterval(pollTimer);
  } catch (error) {
    taskDetail.textContent = error.message;
    clearInterval(pollTimer);
  }
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(form));
  const dryRun = document.querySelector('#dry-run').checked;
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
        dryRun
      })
    });
    const task = await response.json();
    if (!response.ok) throw new Error(task.error || 'La création du ticket a échoué.');
    activeTaskId = task.id;
    renderTask(task);
    clearInterval(pollTimer);
    pollTimer = setInterval(refreshTask, 3000);
    message.textContent = `Ticket ${task.id.slice(0, 8)} envoyé.`;
  } catch (error) {
    message.textContent = error.message;
  } finally {
    submitButton.disabled = false;
    submitButton.innerHTML = 'Envoyer à l\'usine <span aria-hidden="true">→</span>';
  }
});
