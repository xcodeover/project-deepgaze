import { Component } from 'react';

/**
 * Catches render-time exceptions anywhere below it so the user never sees a
 * silent blank page. Without this, a crash inside e.g. ECharts during the
 * first non-empty snapshot would unmount the whole tree and leave only the
 * empty <div id="root"> behind.
 */
export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    console.error('[ErrorBoundary] render crash', error, info);
  }

  render() {
    if (this.state.error) {
      return (
        <div className="boundary">
          <div className="boundary__title">Dashboard crashed</div>
          <pre className="boundary__msg">{String(this.state.error?.stack || this.state.error)}</pre>
          <button
            type="button"
            className="boundary__btn"
            onClick={() => this.setState({ error: null })}
          >
            Try to recover
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
