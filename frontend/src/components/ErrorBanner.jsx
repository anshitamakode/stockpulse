export default function ErrorBanner({ message, onRetry }) {
  return (
    <div className="error-banner" role="alert">
      <span>{message}</span>
      <button onClick={onRetry}>Retry</button>
    </div>
  );
}
