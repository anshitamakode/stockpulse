import { useEffect } from 'react';

export default function Toast({ kind, message, onClose }) {
  useEffect(() => {
    const t = setTimeout(onClose, kind === 'error' ? 6000 : 3500);
    return () => clearTimeout(t);
  }, [message, kind, onClose]);

  return (
    <div className={`toast ${kind}`} role="status" onClick={onClose}>
      {message}
    </div>
  );
}
